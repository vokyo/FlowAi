package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.ai.plan.ProjectPlan;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionService;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueCreationService;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({
        TestcontainersConfiguration.class,
        ProjectPlanApplyIntegrationTests.ClockConfiguration.class
})
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class ProjectPlanApplyIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectWorkflowStateRepository workflowStateRepository;
    @Autowired private IssueRepository issueRepository;
    @Autowired private AiSuggestionRepository suggestionRepository;
    @Autowired private AiSuggestionService suggestionService;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private SteerableClock clock;

    @MockitoSpyBean private IssueCreationService issueCreationService;

    @AfterEach
    void releaseClock() {
        clock.followSystemTime();
    }

    @Test
    void approvingAPlanCreatesEveryTaskOnceAndReplaysTheSameKey() throws Exception {
        Graph graph = graph("approve");
        LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(7);
        AiSuggestion suggestion = savePlan(graph, graph.teammate().getId(), dueDate);
        assertThat(issueRepository.count()).isZero();
        double appliedPlansBefore = appliedProjectPlans();

        UUID key = UUID.randomUUID();
        JsonNode first = readJson(apply(graph, suggestion, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.createdIssueIds.length()").value(3)));

        assertThat(issueRepository.findAll())
                .extracting(Issue::getTitle)
                .containsExactlyInAnyOrder(
                        "Remove the legacy session table",
                        "Add refresh token tests",
                        "Document the login flow"
                );
        Map<String, Object> assigned = jdbcTemplate.queryForMap(
                "select assignee_user_id, priority, due_date from issues where title = ?",
                "Add refresh token tests"
        );
        assertThat(assigned.get("assignee_user_id")).isEqualTo(graph.teammate().getId());
        assertThat(assigned.get("priority")).isEqualTo("HIGH");
        assertThat(((Date) assigned.get("due_date")).toLocalDate()).isEqualTo(dueDate);

        apply(graph, suggestion, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdIssueIds[0]")
                        .value(first.get("createdIssueIds").get(0).asText()));
        assertThat(issueRepository.count()).isEqualTo(3);

        apply(graph, suggestion, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_NOT_DRAFT"));
        assertThat(issueRepository.count()).isEqualTo(3);
        assertThat(appliedProjectPlans() - appliedPlansBefore).isEqualTo(1.0);
    }

    @Test
    void aTaskThatFailsHalfwayRollsBackTheWholePlan() throws Exception {
        Graph graph = graph("rollback");
        AiSuggestion suggestion = savePlan(graph, null, null);
        AtomicInteger creations = new AtomicInteger();
        doAnswer(invocation -> {
            if (creations.incrementAndGet() == 3) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project workflow state not found");
            }
            return invocation.callRealMethod();
        }).when(issueCreationService).create(any(), any(), any());

        apply(graph, suggestion, UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_INVALID"));

        // Two issues were written before the third failed; the transaction must take them back.
        assertThat(creations.get()).isEqualTo(3);
        assertThat(issueRepository.count()).isZero();
        assertThat(statusOf(suggestion)).isEqualTo(AiSuggestionStatus.DRAFT);
    }

    @Test
    void aPlanStaysApprovableAfterOneOfItsDueDatesHasPassed() throws Exception {
        Graph graph = graph("late-approval");
        LocalDate dueOnGenerationDay = LocalDate.now(ZoneOffset.UTC);
        AiSuggestion suggestion = savePlan(graph, null, dueOnGenerationDay);
        // Three days later: still inside the seven-day TTL, but past item-2's due date.
        clock.pinTo(Instant.now().plus(Duration.ofDays(3)));

        apply(graph, suggestion, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.createdIssueIds.length()").value(3));
    }

    @Test
    void aPlanThatExpiresWhileItsIssuesAreBeingCreatedLeavesNoIssuesBehind() throws Exception {
        Graph graph = graph("expires-mid-apply");
        AiSuggestion suggestion = savePlan(graph, null, null);
        Instant expiresAt = suggestion.getExpiresAt();
        clock.pinTo(expiresAt.minusSeconds(1));
        AtomicInteger creations = new AtomicInteger();
        doAnswer(invocation -> {
            Object created = invocation.callRealMethod();
            if (creations.incrementAndGet() == 3) {
                // The deadline passes after the last issue is written and
                // before the suggestion is marked applied.
                clock.pinTo(expiresAt.plusMillis(1));
            }
            return created;
        }).when(issueCreationService).create(any(), any(), any());

        apply(graph, suggestion, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_NOT_DRAFT"));

        assertThat(creations.get()).isEqualTo(3);
        assertThat(issueRepository.count()).isZero();
        mockMvc.perform(get("/api/ai/suggestions/{id}", suggestion.getId())
                        .header("Authorization", bearer(graph.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.createdIssueIds.length()").value(0));
    }

    @Test
    void anAssigneeWhoLeftTheProjectBeforeApprovalFailsTheWholePlan() throws Exception {
        Graph graph = graph("assignee-left");
        AiSuggestion suggestion = savePlan(graph, graph.teammate().getId(), null);
        ProjectMember teammate = projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_Id(
                graph.workspace().getId(),
                graph.project().getId(),
                graph.teammate().getId()
        ).orElseThrow();
        teammate.disable();
        projectMemberRepository.saveAndFlush(teammate);

        apply(graph, suggestion, UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_INVALID"))
                .andExpect(jsonPath("$.message").value(
                        "suggestedAssigneeUserId of item item-2 is not an active project member"));

        assertThat(issueRepository.count()).isZero();
        assertThat(statusOf(suggestion)).isEqualTo(AiSuggestionStatus.DRAFT);
    }

    @Test
    void anArchivedProjectCannotApproveAPlan() throws Exception {
        Graph graph = graph("archived");
        AiSuggestion suggestion = savePlan(graph, null, null);
        Project project = projectRepository.findById(graph.project().getId()).orElseThrow();
        project.archive();
        projectRepository.saveAndFlush(project);

        apply(graph, suggestion, UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("Archived projects cannot apply project plans"));

        assertThat(issueRepository.count()).isZero();
        assertThat(statusOf(suggestion)).isEqualTo(AiSuggestionStatus.DRAFT);
    }

    @Test
    void approvingAPlanCannotEditItsTasks() throws Exception {
        Graph graph = graph("edits");
        AiSuggestion suggestion = savePlan(graph, null, null);

        postJson(
                "/api/ai/suggestions/%s/apply".formatted(suggestion.getId()),
                """
                        {
                          "idempotencyKey": "%s",
                          "items": [{ "clientItemId": "item-1", "selected": true, "title": "Edited" }]
                        }
                        """.formatted(UUID.randomUUID()),
                graph.accessToken()
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Project plans are applied as saved and take no items"));

        assertThat(issueRepository.count()).isZero();
    }

    private ResultActions apply(
            Graph graph,
            AiSuggestion suggestion,
            UUID idempotencyKey
    ) throws Exception {
        return postJson(
                "/api/ai/suggestions/%s/apply".formatted(suggestion.getId()),
                """
                        { "idempotencyKey": "%s" }
                        """.formatted(idempotencyKey),
                graph.accessToken()
        );
    }

    private AiSuggestion savePlan(Graph graph, UUID secondAssignee, LocalDate dueDate) {
        ProjectPlan plan = new ProjectPlan(
                "Clear the login module's technical debt",
                List.of(
                        new ProjectPlan.Item(
                                "item-1",
                                "Remove the legacy session table",
                                "Drop it once nothing reads it",
                                IssuePriority.MEDIUM,
                                null,
                                null
                        ),
                        new ProjectPlan.Item(
                                "item-2",
                                "Add refresh token tests",
                                "Cover rotation and reuse detection",
                                IssuePriority.HIGH,
                                secondAssignee,
                                dueDate
                        ),
                        new ProjectPlan.Item(
                                "item-3",
                                "Document the login flow",
                                null,
                                IssuePriority.LOW,
                                null,
                                null
                        )
                )
        );
        CurrentWorkspaceContext context = new CurrentWorkspaceContext(
                graph.owner(),
                graph.workspace(),
                graph.membership()
        );
        return suggestionService.createDraft(new AiSuggestionService.CreateDraftCommand(
                context,
                graph.project(),
                null,
                AiSuggestionType.PROJECT_PLAN,
                objectMapper.valueToTree(plan),
                "planning-agent-v1",
                "fake",
                "fake-model",
                "goal: clear the login module's technical debt",
                null,
                null
        ));
    }

    private AiSuggestionStatus statusOf(AiSuggestion suggestion) {
        return suggestionRepository.findById(suggestion.getId()).orElseThrow().getStatus();
    }

    private double appliedProjectPlans() {
        return meterRegistry.counter(
                "flowai.ai.suggestions",
                "type", "project_plan",
                "status", "applied"
        ).count();
    }

    private Graph graph(String suffix) {
        long unique = System.nanoTime();
        User owner = userRepository.save(new User(
                suffix + "-" + unique + "@example.com",
                "password-hash",
                "Owner"
        ));
        User teammate = userRepository.save(new User(
                suffix + "-teammate-" + unique + "@example.com",
                "password-hash",
                "Teammate"
        ));
        Workspace workspace = workspaceRepository.save(new Workspace(
                owner,
                "Workspace " + suffix,
                suffix + "-" + unique
        ));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
                workspace,
                owner,
                WorkspaceRole.OWNER
        ));
        membershipRepository.save(new WorkspaceMembership(workspace, teammate, WorkspaceRole.MEMBER));
        Project project = projectRepository.save(new Project(
                workspace,
                owner,
                "Project " + suffix,
                "Project description"
        ));
        projectMemberRepository.save(new ProjectMember(workspace, project, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(workspace, project, teammate, ProjectRole.MEMBER));
        workflowStateRepository.save(new ProjectWorkflowState(
                workspace,
                project,
                "Todo",
                WorkflowStateCategory.TODO,
                10_000
        ));
        return new Graph(
                owner,
                teammate,
                workspace,
                membership,
                project,
                jwtService.generateAccessToken(owner, membership)
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        SteerableClock steerableClock() {
            return new SteerableClock();
        }
    }

    /**
     * Follows the system clock until a test pins it, so the other tests in this
     * class still see real time.
     */
    static final class SteerableClock extends Clock {

        private volatile Instant pinned;

        void pinTo(Instant instant) {
            pinned = instant;
        }

        void followSystemTime() {
            pinned = null;
        }

        @Override
        public Instant instant() {
            Instant current = pinned;
            return current == null ? Instant.now() : current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("These tests only need UTC");
        }
    }

    private record Graph(
            User owner,
            User teammate,
            Workspace workspace,
            WorkspaceMembership membership,
            Project project,
            String accessToken
    ) {
    }
}
