package com.vokyo.backend;

import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.ai.breakdown.IssueBreakdownResult;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.Issue;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({
        TestcontainersConfiguration.class,
        IssueBreakdownIntegrationTests.FakeAiConfiguration.class
})
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "spring.ai.openai.api-key=dummy"
})
class IssueBreakdownIntegrationTests extends AbstractMockMvcIntegrationTest {

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
    private AiSuggestionRepository suggestionRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private FakeAiGateway fakeGateway;

    @BeforeEach
    void resetGateway() {
        fakeGateway.calls.set(0);
    }

    @Test
    void generatesAndPersistsAValidatedDraftSuggestion() throws Exception {
        TenantGraph graph = createTenantGraph("success");

        mockMvc.perform(get("/api/ai/status")
                        .header("Authorization", bearer(graph.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.breakdownAvailable").value(true));

        postJson(
                "/api/ai/issues/%s/breakdown".formatted(graph.issue().getId()),
                """
                {
                  "instruction": "Focus on the MVP",
                  "maxItems": 4,
                  "includeComments": false,
                  "includeActivity": false
                }
                """,
                graph.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ISSUE_BREAKDOWN"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.projectId").value(graph.project().getId().toString()))
                .andExpect(jsonPath("$.sourceIssueId").value(graph.issue().getId().toString()))
                .andExpect(jsonPath("$.content.overview").value("Delivery plan"))
                .andExpect(jsonPath("$.content.items.length()").value(2))
                .andExpect(jsonPath("$.content.items[1].dependsOnClientItemIds[0]").value("item-1"))
                .andExpect(jsonPath("$.metadata.promptVersion").value("issue-breakdown-v1"))
                .andExpect(jsonPath("$.metadata.contextTruncated").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        assertThat(fakeGateway.calls).hasValue(1);
        assertThat(suggestionRepository.findAll()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.getType()).isEqualTo(AiSuggestionType.ISSUE_BREAKDOWN);
            assertThat(suggestion.getStatus()).isEqualTo(AiSuggestionStatus.DRAFT);
            assertThat(suggestion.getWorkspace().getId()).isEqualTo(graph.workspace().getId());
            assertThat(suggestion.getProject().getId()).isEqualTo(graph.project().getId());
            assertThat(suggestion.getSourceIssue().getId()).isEqualTo(graph.issue().getId());
            assertThat(suggestion.getContent().get("items").size()).isEqualTo(2);
            assertThat(suggestion.getProvider()).isEqualTo("fake");
            assertThat(suggestion.getModel()).isEqualTo("fake-model");
            assertThat(suggestion.getInputTokens()).isEqualTo(21);
            assertThat(suggestion.getOutputTokens()).isEqualTo(13);
            assertThat(suggestion.getInputHash()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void tenantScopedLookupDoesNotCallTheModelForAnotherWorkspaceIssue() throws Exception {
        TenantGraph tenantA = createTenantGraph("tenant-a");
        TenantGraph tenantB = createTenantGraph("tenant-b");

        postJson(
                "/api/ai/issues/%s/breakdown".formatted(tenantB.issue().getId()),
                "{}",
                tenantA.accessToken()
        ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(fakeGateway.calls).hasValue(0);
        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void archivedSourceReturnsStableRequestErrorWithoutCallingTheModel() throws Exception {
        TenantGraph graph = createTenantGraph("archived");
        graph.issue().archive();
        issueRepository.saveAndFlush(graph.issue());

        postJson(
                "/api/ai/issues/%s/breakdown".formatted(graph.issue().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_REQUEST_INVALID"));

        assertThat(fakeGateway.calls).hasValue(0);
        assertThat(suggestionRepository.count()).isZero();
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
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
                workspace,
                user,
                WorkspaceRole.OWNER
        ));
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
        String accessToken = jwtService.generateAccessToken(user, membership);
        return new TenantGraph(user, workspace, project, issue, accessToken);
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
        FakeAiGateway fakeAiGateway() {
            return new FakeAiGateway();
        }
    }

    static final class FakeAiGateway implements AiModelGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> AiGeneration<T> generate(
                String systemPrompt,
                String userPrompt,
                Class<T> responseType
        ) {
            calls.incrementAndGet();
            IssueBreakdownResult result = new IssueBreakdownResult(
                    "Delivery plan",
                    List.of(
                            item("item-1", List.of()),
                            item("item-2", List.of("item-1"))
                    ),
                    List.of()
            );
            return new AiGeneration<>(
                    responseType.cast(result),
                    "{\"overview\":\"Delivery plan\"}",
                    "fake",
                    "fake-model",
                    21,
                    13
            );
        }

        private IssueBreakdownResult.Item item(String id, List<String> dependencies) {
            return new IssueBreakdownResult.Item(
                    id,
                    "Task " + id,
                    "Description",
                    IssuePriority.HIGH,
                    List.of("It works"),
                    List.of(),
                    null,
                    null,
                    dependencies
            );
        }
    }
}
