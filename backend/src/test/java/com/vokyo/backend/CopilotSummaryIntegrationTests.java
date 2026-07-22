package com.vokyo.backend;

import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.ai.summary.issue.IssueSummaryModelOutput;
import com.vokyo.backend.ai.summary.project.ProjectSummaryModelOutput;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueComment;
import com.vokyo.backend.issue.IssueCommentRepository;
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
import com.vokyo.backend.security.ratelimit.RateLimitProperties;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({
        TestcontainersConfiguration.class,
        CopilotSummaryIntegrationTests.FakeAiConfiguration.class
})
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.include-comments-limit=2",
        "app.ai.include-activity-limit=2",
        "app.ai.max-context-issues=2",
        "app.ai.rate-limit.capacity=2",
        "app.ai.rate-limit.enabled=true",
        "app.ai.rate-limit.window=1m",
        "app.security.rate-limit.enabled=true",
        "spring.ai.openai.api-key=dummy"
})
class CopilotSummaryIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private ProjectWorkflowStateRepository workflowStateRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private AiSuggestionRepository suggestionRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private FakeSummaryGateway gateway;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Test
    void summarizesArchivedIssuePersistsValidatedDraftAndReportsTruncation() throws Exception {
        TenantGraph graph = createTenantGraph("issue-summary");
        gateway.calls.set(0);
        commentRepository.saveAll(List.of(
                comment(graph, "Old comment"),
                comment(graph, "Recent comment"),
                comment(graph, "Newest comment")
        ));
        graph.issue().archive();
        issueRepository.saveAndFlush(graph.issue());

        postJson(
                "/api/ai/issues/%s/summary".formatted(graph.issue().getId()),
                "{\"includeComments\":true,\"includeActivity\":false}",
                graph.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ISSUE_SUMMARY"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.content.summary").value("The issue is ready for review."))
                .andExpect(jsonPath("$.content.decisions[0]").value("Use the validated API contract."))
                .andExpect(jsonPath("$.content.sourceStats.commentsUsed").value(2))
                .andExpect(jsonPath("$.content.sourceStats.commentsTruncated").value(true))
                .andExpect(jsonPath("$.metadata.contextTruncated").value(true));

        assertThat(gateway.calls).hasValue(1);
        assertThat(gateway.lastUserPrompt)
                .contains("Recent comment", "Newest comment")
                .doesNotContain("Old comment");
        assertThat(suggestionRepository.findAll()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.getType()).isEqualTo(AiSuggestionType.ISSUE_SUMMARY);
            assertThat(suggestion.getStatus()).isEqualTo(AiSuggestionStatus.DRAFT);
            assertThat(suggestion.isContextTruncated()).isTrue();
            assertThat(suggestion.getProvider()).isEqualTo("fake");
            assertThat(suggestion.getInputTokens()).isEqualTo(11);
            assertThat(suggestion.getOutputTokens()).isEqualTo(7);
        });
    }

    @Test
    void blocksCrossTenantIssueSummaryBeforeCallingProvider() throws Exception {
        TenantGraph tenantA = createTenantGraph("summary-a");
        TenantGraph tenantB = createTenantGraph("summary-b");
        gateway.calls.set(0);

        postJson(
                "/api/ai/issues/%s/summary".formatted(tenantB.issue().getId()),
                "{}",
                tenantA.accessToken()
        ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(gateway.calls).hasValue(0);
        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void summarizesProjectWithDeterministicPriorityAndTruncation() throws Exception {
        TenantGraph graph = createTenantGraph("project-summary");
        ProjectWorkflowState workflowState = graph.issue().getWorkflowState();
        Issue urgent = issueRepository.save(new Issue(
                graph.workspace(), graph.project(), graph.user(),
                "Urgent active issue", "Urgent description", null,
                workflowState, IssuePriority.URGENT, null, 20_000L
        ));
        Issue overdue = issueRepository.save(new Issue(
                graph.workspace(), graph.project(), graph.user(),
                "Overdue issue", "Overdue description", null,
                workflowState, IssuePriority.LOW, LocalDate.now().minusDays(5), 30_000L
        ));
        issueRepository.flush();
        gateway.calls.set(0);

        postJson(
                "/api/ai/projects/%s/summary".formatted(graph.project().getId()),
                "{\"rangeDays\":30,\"focus\":\"Delivery risk\"}",
                graph.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PROJECT_SUMMARY"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sourceIssueId").doesNotExist())
                .andExpect(jsonPath("$.content.executiveSummary").value("Delivery is progressing with one material risk."))
                .andExpect(jsonPath("$.content.sourceStats.activeIssuesUsed").value(2))
                .andExpect(jsonPath("$.content.sourceStats.totalActiveIssues").value(3))
                .andExpect(jsonPath("$.content.sourceStats.rangeDays").value(30))
                .andExpect(jsonPath("$.metadata.contextTruncated").value(true));

        assertThat(gateway.calls).hasValue(1);
        assertThat(gateway.lastUserPrompt.indexOf(overdue.getTitle()))
                .isLessThan(gateway.lastUserPrompt.indexOf(urgent.getTitle()));
        assertThat(gateway.lastUserPrompt).doesNotContain("Source issue");
        assertThat(suggestionRepository.findAll()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.getType()).isEqualTo(AiSuggestionType.PROJECT_SUMMARY);
            assertThat(suggestion.getSourceIssue()).isNull();
            assertThat(suggestion.isContextTruncated()).isTrue();
        });
    }

    @Test
    void allowsArchivedProjectSummaryAndRejectsUnsupportedRange() throws Exception {
        TenantGraph graph = createTenantGraph("archived-project-summary");
        graph.project().archive();
        projectRepository.saveAndFlush(graph.project());

        postJson(
                "/api/ai/projects/%s/summary".formatted(graph.project().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.sourceStats.rangeDays").value(30));

        gateway.calls.set(0);
        postJson(
                "/api/ai/projects/%s/summary".formatted(graph.project().getId()),
                "{\"rangeDays\":14}",
                graph.accessToken()
        ).andExpect(status().isBadRequest());
        assertThat(gateway.calls).hasValue(0);
    }

    @Test
    void blocksCrossTenantProjectSummaryBeforeCallingProvider() throws Exception {
        TenantGraph tenantA = createTenantGraph("project-summary-a");
        TenantGraph tenantB = createTenantGraph("project-summary-b");
        gateway.calls.set(0);

        postJson(
                "/api/ai/projects/%s/summary".formatted(tenantB.project().getId()),
                "{}",
                tenantA.accessToken()
        ).andExpect(status().isNotFound());

        assertThat(gateway.calls).hasValue(0);
        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void sharesAiGenerationQuotaAcrossSummaryFeatures() throws Exception {
        TenantGraph graph = createTenantGraph("shared-ai-quota");
        gateway.calls.set(0);
        assertThat(aiProperties.rateLimit().capacity()).isEqualTo(2);
        assertThat(aiProperties.rateLimit().enabled()).isTrue();
        assertThat(rateLimitProperties.enabled()).isTrue();

        postJson(
                "/api/ai/issues/%s/summary".formatted(graph.issue().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isOk());
        postJson(
                "/api/ai/projects/%s/summary".formatted(graph.project().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isOk());
        postJson(
                "/api/ai/issues/%s/summary".formatted(graph.issue().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));

        assertThat(gateway.calls).hasValue(2);
    }

    private IssueComment comment(TenantGraph graph, String body) {
        return new IssueComment(
                graph.workspace(),
                graph.project(),
                graph.issue(),
                graph.user(),
                body
        );
    }

    private TenantGraph createTenantGraph(String suffix) {
        long unique = System.nanoTime();
        User user = userRepository.save(new User(
                suffix + "-" + unique + "@example.com",
                "password-hash",
                "Test User"
        ));
        Workspace workspace = workspaceRepository.save(new Workspace(
                user,
                "Workspace " + suffix,
                suffix + "-" + unique
        ));
        WorkspaceMembership membership = membershipRepository.save(
                new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER)
        );
        Project project = projectRepository.save(new Project(
                workspace,
                user,
                "Project " + suffix,
                "Project description"
        ));
        projectMemberRepository.save(new ProjectMember(
                workspace,
                project,
                user,
                ProjectRole.OWNER
        ));
        ProjectWorkflowState workflowState = workflowStateRepository.save(
                new ProjectWorkflowState(
                        workspace,
                        project,
                        "Todo",
                        WorkflowStateCategory.TODO,
                        10_000
                )
        );
        Issue issue = issueRepository.save(new Issue(
                workspace,
                project,
                user,
                "Source issue",
                "Source description",
                null,
                workflowState,
                IssuePriority.HIGH,
                null,
                10_000L
        ));
        return new TenantGraph(
                user,
                workspace,
                project,
                issue,
                jwtService.generateAccessToken(user, membership)
        );
    }

    private record TenantGraph(
            User user,
            Workspace workspace,
            Project project,
            Issue issue,
            String accessToken
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeAiConfiguration {

        @Bean
        FakeSummaryGateway fakeSummaryGateway() {
            return new FakeSummaryGateway();
        }
    }

    static final class FakeSummaryGateway implements AiModelGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private String lastUserPrompt;

        @Override
        public <T> AiGeneration<T> generate(
                String systemPrompt,
                String userPrompt,
                Class<T> responseType
        ) {
            calls.incrementAndGet();
            lastUserPrompt = userPrompt;
            Object output = responseType == ProjectSummaryModelOutput.class
                    ? new ProjectSummaryModelOutput(
                            "Delivery is progressing with one material risk.",
                            List.of("Core work is active."),
                            List.of("An issue is overdue."),
                            List.of(),
                            List.of("Some work remains unassigned."),
                            List.of("Resolve the overdue issue.")
                    )
                    : new IssueSummaryModelOutput(
                            "The issue is ready for review.",
                            List.of("Use the validated API contract."),
                            List.of(),
                            List.of(),
                            List.of("Run the integration suite.")
                    );
            return new AiGeneration<>(
                    responseType.cast(output),
                    "{\"summary\":\"The issue is ready for review.\"}",
                    "fake",
                    "fake-model",
                    11,
                    7
            );
        }
    }
}
