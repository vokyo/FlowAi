package com.vokyo.backend.integration;

import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.ai.TextEmbedder;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The full-text and semantic modes of the agent's issue search, against real PostgreSQL
 * with pgvector. Embeddings come from a fixed fake, so no test calls a paid API.
 */
@Import({TestcontainersConfiguration.class, AgentIssueSearchModesIntegrationTests.FakeEmbeddingConfiguration.class})
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AgentIssueSearchModesIntegrationTests {

    private static final String ISSUES = "/api/internal/agent/project/issues";
    private static final int DIMENSIONS = 1536;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AgentTokenService agentTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectWorkflowStateRepository workflowStateRepository;
    @Autowired private IssueRepository issueRepository;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    private long nextBoardPosition;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
        nextBoardPosition = 10_000;
    }

    @Test
    void fullTextMatchesOtherFormsOfTheQueryWordsWhereKeywordSearchDoesNot() throws Exception {
        Fixture fixture = fixture("forms");
        issue(fixture, "Rate limit the login endpoint", "Throttle repeated sign-in attempts per email.");

        search(fixture, "keyword", "limits")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", empty()));
        search(fixture, "fulltext", "limits")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title", contains("Rate limit the login endpoint")));
        search(fixture, "fulltext", "logins")
            .andExpect(jsonPath("$.items[*].title", contains("Rate limit the login endpoint")));
    }

    @Test
    void fullTextRanksTitleMatchesAboveDescriptionMatches() throws Exception {
        Fixture fixture = fixture("rank");
        // The title match sits between two description matches, so neither insertion order
        // nor newest-first order alone can produce the expected ranking.
        issue(fixture, "Write the reporting guide", "Explain where CSV files come from.");
        issue(fixture, "Export a project's issues as CSV", "Download the board as a spreadsheet.");
        issue(fixture, "Document the import format", "List the CSV columns we accept.");

        search(fixture, "fulltext", "csv")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title", contains(
                "Export a project's issues as CSV",
                "Document the import format",
                "Write the reporting guide")));
    }

    @Test
    void fullTextNeedsEveryWordOfTheQuery() throws Exception {
        Fixture fixture = fixture("words");
        issue(fixture, "Rate limit the login endpoint", "Throttle repeated sign-in attempts per email.");

        search(fixture, "fulltext", "login rate")
            .andExpect(jsonPath("$.items[*].title", contains("Rate limit the login endpoint")));
        search(fixture, "fulltext", "login billing")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void fullTextOnlySearchesActiveIssuesOfTheTokensProject() throws Exception {
        Fixture fixture = fixture("scope");
        issue(fixture, "Audit login failures", "Count failed sign-ins.");
        archive(issue(fixture, "Retire the legacy login page", "Old page."));
        Fixture other = siblingProject(fixture, "scope-other");
        issue(other, "Login for the other project", "Same workspace, different project.");

        search(fixture, "fulltext", "login")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title", contains("Audit login failures")));
    }

    @Test
    void aQueryOfOnlyCommonWordsFindsNothingRatherThanFailing() throws Exception {
        Fixture fixture = fixture("stopwords");
        issue(fixture, "The board is slow", "The columns load one by one.");

        search(fixture, "fulltext", "the")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void withoutAQueryEveryModeListsTheNewestIssues() throws Exception {
        Fixture fixture = fixture("noquery");
        issue(fixture, "Older issue", "First.");
        issue(fixture, "Newer issue", "Second.");

        for (String mode : new String[] {"keyword", "fulltext", "semantic"}) {
            asAgent(fixture, get(ISSUES).param("mode", mode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title", contains("Newer issue", "Older issue")));
        }
    }

    @Test
    void semanticSearchRanksByClosenessAndAlwaysFillsTheLimit() throws Exception {
        Fixture fixture = fixture("semantic");
        embed(fixture, issue(fixture, "Rate limit the login endpoint", "Throttle sign-ins."), direction(1, 0));
        embed(fixture, issue(fixture, "Rotate refresh tokens", "Shorter sessions."), direction(0.8, 0.6));
        embed(fixture, issue(fixture, "Export issues as CSV", "Spreadsheet download."), direction(0, 1));

        search(fixture, "semantic", "authentication", 2)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title",
                contains("Rate limit the login endpoint", "Rotate refresh tokens")))
            .andExpect(jsonPath("$.truncated").value(true));
        // Nearest neighbours always come back, related or not: there is no "nothing found".
        search(fixture, "semantic", "authentication", 3)
            .andExpect(jsonPath("$.items[*].title", contains(
                "Rate limit the login endpoint", "Rotate refresh tokens", "Export issues as CSV")))
            .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void semanticSearchSkipsArchivedIssuesOtherProjectsAndIssuesWithoutAnEmbedding() throws Exception {
        Fixture fixture = fixture("semantic-scope");
        embed(fixture, issue(fixture, "Audit login failures", "Count failed sign-ins."), direction(1, 0));
        Issue archived = issue(fixture, "Retire the legacy login page", "Old page.");
        embed(fixture, archived, direction(1, 0));
        archive(archived);
        issue(fixture, "Login issue not embedded yet", "No vector stored.");
        Fixture other = siblingProject(fixture, "semantic-other");
        embed(other, issue(other, "Login for the other project", "Same workspace."), direction(1, 0));

        search(fixture, "semantic", "authentication")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title", contains("Audit login failures")));
    }

    @Test
    void aFailingEmbeddingCallIsReportedAsUnavailable() throws Exception {
        Fixture fixture = fixture("embedding-down");
        issue(fixture, "Audit login failures", "Count failed sign-ins.");

        search(fixture, "semantic", FakeEmbeddingConfiguration.FAILING_QUERY)
            .andExpect(status().isServiceUnavailable());
    }

    private ResultActions search(Fixture fixture, String mode, String query) throws Exception {
        return asAgent(fixture, get(ISSUES).param("mode", mode).param("q", query));
    }

    private ResultActions search(Fixture fixture, String mode, String query, int limit) throws Exception {
        return asAgent(fixture, get(ISSUES)
            .param("mode", mode)
            .param("q", query)
            .param("limit", String.valueOf(limit)));
    }

    private ResultActions asAgent(
        Fixture fixture,
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) throws Exception {
        CurrentWorkspaceContext context = new CurrentWorkspaceContext(
            fixture.owner(), fixture.workspace(), fixture.membership());
        String token = agentTokenService.issue(context, fixture.project().getId(), UUID.randomUUID());
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private Fixture fixture(String name) {
        long unique = System.nanoTime();
        User owner = userRepository.save(new User(
            name + "-" + unique + "@example.com", "password-hash", "Owner " + name));
        Workspace workspace = workspaceRepository.save(new Workspace(
            owner, "Workspace " + name, name + "-" + unique));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
            workspace, owner, WorkspaceRole.OWNER));
        return siblingProject(new Fixture(owner, workspace, membership, null, null), name);
    }

    /** Another project in the same workspace, so only the project filter can keep it out. */
    private Fixture siblingProject(Fixture fixture, String name) {
        Project project = projectRepository.save(new Project(
            fixture.workspace(), fixture.owner(), name, "Project " + name));
        projectMemberRepository.save(new ProjectMember(
            fixture.workspace(), project, fixture.owner(), ProjectRole.OWNER));
        ProjectWorkflowState todo = workflowStateRepository.save(new ProjectWorkflowState(
            fixture.workspace(), project, "Todo", WorkflowStateCategory.TODO, 10_000));
        return new Fixture(fixture.owner(), fixture.workspace(), fixture.membership(), project, todo);
    }

    private Issue issue(Fixture fixture, String title, String description) {
        nextBoardPosition += 10_000;
        return issueRepository.saveAndFlush(new Issue(
            fixture.workspace(),
            fixture.project(),
            fixture.owner(),
            title,
            description,
            null,
            fixture.todo(),
            null,
            null,
            nextBoardPosition
        ));
    }

    private void archive(Issue issue) {
        issue.archive();
        issueRepository.saveAndFlush(issue);
    }

    private void embed(Fixture fixture, Issue issue, float[] vector) {
        jdbcTemplate.update("""
                insert into issue_embeddings
                    (issue_id, workspace_id, project_id, model, content_hash, embedding, embedded_at)
                values (?, ?, ?, 'test-model', ?, cast(? as vector), now())
                """,
            issue.getId(),
            fixture.workspace().getId(),
            fixture.project().getId(),
            "0".repeat(64),
            literal(vector)
        );
    }

    /**
     * A vector pointing mostly along the first two axes. The tiny third component makes
     * the text form use exponent notation (1.0E-6), which pgvector must parse too.
     */
    static float[] direction(double first, double second) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = (float) first;
        vector[1] = (float) second;
        vector[2] = 1.0E-6f;
        return vector;
    }

    private static String literal(float[] vector) {
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            text.append(index == 0 ? "" : ",").append(vector[index]);
        }
        return text.append(']').toString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEmbeddingConfiguration {

        static final String FAILING_QUERY = "embedding provider is down";

        @Bean
        TextEmbedder textEmbedder() {
            return text -> switch (text) {
                case "authentication" -> direction(1, 0);
                case FAILING_QUERY -> throw new IllegalStateException("provider unavailable");
                default -> throw new IllegalArgumentException("no fake embedding for: " + text);
            };
        }
    }

    private record Fixture(
        User owner,
        Workspace workspace,
        WorkspaceMembership membership,
        Project project,
        ProjectWorkflowState todo
    ) {
    }
}
