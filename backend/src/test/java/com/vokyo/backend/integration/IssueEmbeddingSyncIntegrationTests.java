package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.ai.TextEmbedder;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueCommandService;
import com.vokyo.backend.issue.IssueCreationCommand;
import com.vokyo.backend.issue.IssueCreationService;
import com.vokyo.backend.issue.IssueEmbeddingWorker;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueStatus;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The embedding outbox end to end: issue writes leave a request in the same transaction,
 * and the worker turns requests into stored vectors. The scheduler is off in tests, so
 * each test runs the worker itself; embeddings come from a recording fake.
 */
@Import({TestcontainersConfiguration.class, IssueEmbeddingSyncIntegrationTests.RecordingEmbedderConfiguration.class})
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class IssueEmbeddingSyncIntegrationTests {

    private static final int DIMENSIONS = 1536;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IssueCreationService issueCreationService;
    @Autowired private IssueCommandService issueCommandService;
    @Autowired private IssueEmbeddingWorker worker;
    @Autowired private RecordingEmbedder embedder;
    @Autowired private AgentTokenService agentTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectWorkflowStateRepository workflowStateRepository;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        embedder.reset();
    }

    @Test
    void creatingAnIssueRequestsAnEmbeddingThatTheWorkerStores() {
        Fixture fixture = fixture("create");
        Issue issue = create(fixture, "Rate limit the login endpoint", "Throttle repeated sign-ins.");

        assertThat(job(issue)).containsEntry("revision", 1L).containsEntry("attempts", 0);

        assertThat(worker.processBatch()).isEqualTo(1);

        String text = "Rate limit the login endpoint\n\nThrottle repeated sign-ins.";
        assertThat(embedder.texts).containsExactly(text);
        Map<String, Object> stored = embedding(issue);
        assertThat(stored.get("model")).isEqualTo("text-embedding-3-small");
        assertThat(stored.get("content_hash")).isEqualTo(sha256(text));
        assertThat(stored.get("project_id")).isEqualTo(fixture.project().getId());
        assertThat(jobCount(issue)).isZero();
    }

    @Test
    void editingTheTitleOrDescriptionRequestsANewEmbedding() {
        Fixture fixture = fixture("edit-text");
        Issue issue = create(fixture, "Rate limit the login endpoint", null);
        worker.processBatch();

        update(fixture, issue, Map.of("title", "Export issues as CSV"));
        worker.processBatch();
        update(fixture, issue, Map.of("description", "Spreadsheet download."));
        worker.processBatch();

        assertThat(embedder.texts).containsExactly(
            "Rate limit the login endpoint",
            "Export issues as CSV",
            "Export issues as CSV\n\nSpreadsheet download."
        );
        assertThat(embedding(issue).get("content_hash"))
            .isEqualTo(sha256("Export issues as CSV\n\nSpreadsheet download."));
    }

    @Test
    void editingOnlyThePriorityRequestsNoEmbedding() {
        Fixture fixture = fixture("edit-priority");
        Issue issue = create(fixture, "Rate limit the login endpoint", null);
        worker.processBatch();

        update(fixture, issue, Map.of("priority", "URGENT"));

        assertThat(jobCount(issue)).isZero();
        assertThat(worker.processBatch()).isZero();
        assertThat(embedder.texts).hasSize(1);
    }

    @Test
    void aRequestForTextThatIsAlreadyEmbeddedSkipsTheApi() {
        Fixture fixture = fixture("unchanged");
        Issue issue = create(fixture, "Rate limit the login endpoint", null);
        worker.processBatch();

        // A title changed and changed back before the worker ran.
        update(fixture, issue, Map.of("title", "Rate limit login"));
        update(fixture, issue, Map.of("title", "Rate limit the login endpoint"));
        worker.processBatch();

        assertThat(embedder.texts).hasSize(1);
        assertThat(jobCount(issue)).isZero();
    }

    @Test
    void aFailedEmbeddingIsRetriedLaterRatherThanAtOnce() {
        Fixture fixture = fixture("failure");
        Issue issue = create(fixture, "Rate limit the login endpoint", null);
        embedder.failNext = true;

        assertThat(worker.processBatch()).isEqualTo(1);

        Map<String, Object> job = job(issue);
        assertThat(job.get("attempts")).isEqualTo(1);
        assertThat((String) job.get("last_error")).contains("provider unavailable");
        assertThat(secondsUntilNextAttempt(issue)).isBetween(25.0, 35.0);
        assertThat(worker.processBatch()).isZero();
        assertThat(embeddingCount(issue)).isZero();
    }

    @Test
    void anEditWhileTheOldTextIsBeingEmbeddedKeepsItsOwnRequest() {
        Fixture fixture = fixture("concurrent-edit");
        Issue issue = create(fixture, "Rate limit the login endpoint", null);
        embedder.duringNextEmbed = () -> requestAgain(issue);

        worker.processBatch();

        // The old text was stored, but the newer request survived for the next run.
        assertThat(embeddingCount(issue)).isEqualTo(1);
        assertThat(job(issue)).containsEntry("revision", 2L).containsEntry("attempts", 0);
        assertThat(worker.processBatch()).isEqualTo(1);
    }

    @Test
    void aSecondWorkerRunSkipsJobsTheFirstHasClaimed() {
        Fixture fixture = fixture("lease");
        create(fixture, "Rate limit the login endpoint", null);
        List<Integer> claimedBySecondRun = new ArrayList<>();
        embedder.duringNextEmbed = () -> claimedBySecondRun.add(worker.processBatch());

        assertThat(worker.processBatch()).isEqualTo(1);

        assertThat(claimedBySecondRun).containsExactly(0);
        assertThat(embedder.texts).hasSize(1);
    }

    @Test
    void semanticSearchFindsAnIssueOnceTheWorkerHasEmbeddedIt() throws Exception {
        Fixture fixture = fixture("end-to-end");
        create(fixture, "Rate limit the login endpoint", "Throttle repeated sign-ins.");
        create(fixture, "Export issues as CSV", "Spreadsheet download.");
        String token = agentTokenService.issue(
            new CurrentWorkspaceContext(fixture.owner(), fixture.workspace(), fixture.membership()),
            fixture.project().getId(),
            UUID.randomUUID()
        );

        mockMvc.perform(get("/api/internal/agent/project/issues")
                .param("mode", "semantic").param("q", "authentication")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", empty()));

        worker.processBatch();

        mockMvc.perform(get("/api/internal/agent/project/issues")
                .param("mode", "semantic").param("q", "authentication").param("limit", "1")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title", contains("Rate limit the login endpoint")));
    }

    private Issue create(Fixture fixture, String title, String description) {
        return issueCreationService.create(
            new CurrentWorkspaceContext(fixture.owner(), fixture.workspace(), fixture.membership()),
            fixture.project(),
            new IssueCreationCommand(title, description, List.of(), null, null, IssueStatus.TODO, IssuePriority.LOW, null)
        );
    }

    private void update(Fixture fixture, Issue issue, Map<String, String> patch) {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(fixture.owner().getId().toString())
            .claim("membershipId", fixture.membership().getId().toString())
            .build();
        issueCommandService.updateIssue(jwt, issue.getId(), objectMapper.valueToTree(patch));
    }

    private void requestAgain(Issue issue) {
        jdbcTemplate.update("""
            update issue_embedding_jobs
            set revision = revision + 1, requested_at = now(), next_attempt_at = now(),
                attempts = 0, last_error = null
            where issue_id = ?
            """, issue.getId());
    }

    private Map<String, Object> job(Issue issue) {
        return jdbcTemplate.queryForMap(
            "select revision, attempts, last_error from issue_embedding_jobs where issue_id = ?", issue.getId());
    }

    private int jobCount(Issue issue) {
        return jdbcTemplate.queryForObject(
            "select count(*) from issue_embedding_jobs where issue_id = ?", Integer.class, issue.getId());
    }

    private double secondsUntilNextAttempt(Issue issue) {
        return jdbcTemplate.queryForObject(
            "select extract(epoch from next_attempt_at - now()) from issue_embedding_jobs where issue_id = ?",
            Double.class, issue.getId());
    }

    private Map<String, Object> embedding(Issue issue) {
        return jdbcTemplate.queryForMap(
            "select model, content_hash, project_id from issue_embeddings where issue_id = ?", issue.getId());
    }

    private int embeddingCount(Issue issue) {
        return jdbcTemplate.queryForObject(
            "select count(*) from issue_embeddings where issue_id = ?", Integer.class, issue.getId());
    }

    private Fixture fixture(String name) {
        long unique = System.nanoTime();
        User owner = userRepository.save(new User(
            name + "-" + unique + "@example.com", "password-hash", "Owner " + name));
        Workspace workspace = workspaceRepository.save(new Workspace(
            owner, "Workspace " + name, name + "-" + unique));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
            workspace, owner, WorkspaceRole.OWNER));
        Project project = projectRepository.save(new Project(workspace, owner, name, "Project " + name));
        projectMemberRepository.save(new ProjectMember(workspace, project, owner, ProjectRole.OWNER));
        workflowStateRepository.save(new ProjectWorkflowState(
            workspace, project, "Todo", WorkflowStateCategory.TODO, 10_000));
        return new Fixture(owner, workspace, membership, project);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Points texts about logins one way and everything else another, and records each call. */
    static final class RecordingEmbedder implements TextEmbedder {

        final List<String> texts = new ArrayList<>();
        boolean failNext;
        Runnable duringNextEmbed;

        @Override
        public float[] embed(String text) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("provider unavailable");
            }
            if (duringNextEmbed != null) {
                Runnable hook = duringNextEmbed;
                duringNextEmbed = null;
                hook.run();
            }
            boolean aboutSignIn = text.equals("authentication") || text.toLowerCase().contains("login");
            if (!text.equals("authentication")) {
                texts.add(text);
            }
            float[] vector = new float[DIMENSIONS];
            vector[aboutSignIn ? 0 : 1] = 1;
            return vector;
        }

        void reset() {
            texts.clear();
            failNext = false;
            duringNextEmbed = null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingEmbedderConfiguration {

        @Bean
        RecordingEmbedder textEmbedder() {
            return new RecordingEmbedder();
        }
    }

    private record Fixture(User owner, Workspace workspace, WorkspaceMembership membership, Project project) {
    }
}
