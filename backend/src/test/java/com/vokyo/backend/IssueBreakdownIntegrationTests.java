package com.vokyo.backend;

import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.activity.ActivityEventRepository;
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
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private ActivityEventRepository activityEventRepository;

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

    @Test
    void suggestionCanOnlyBeReadAndDismissedByItsCreator() throws Exception {
        TenantGraph owner = createTenantGraph("suggestion-owner");
        TenantGraph other = createTenantGraph("suggestion-other");

        postJson(
                "/api/ai/issues/%s/breakdown".formatted(owner.issue().getId()),
                "{}",
                owner.accessToken()
        ).andExpect(status().isOk());

        var suggestion = suggestionRepository.findAll().getFirst();

        mockMvc.perform(get(
                        "/api/ai/suggestions/{suggestionId}",
                        suggestion.getId()
                ).header("Authorization", bearer(other.accessToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_NOT_FOUND"));

        mockMvc.perform(get(
                        "/api/ai/suggestions/{suggestionId}",
                        suggestion.getId()
                ).header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.metadata.contextTruncated").value(false))
                .andExpect(jsonPath("$.createdIssueIds.length()").value(0));

        mockMvc.perform(post(
                        "/api/ai/suggestions/{suggestionId}/dismiss",
                        suggestion.getId()
                ).header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.dismissedAt").isNotEmpty());

        mockMvc.perform(post(
                        "/api/ai/suggestions/{suggestionId}/dismiss",
                        suggestion.getId()
                ).header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_NOT_DRAFT"));

        assertThat(suggestionRepository.findById(suggestion.getId()))
                .get()
                .extracting(com.vokyo.backend.ai.suggestion.AiSuggestion::getStatus)
                .isEqualTo(AiSuggestionStatus.DISMISSED);
    }

    @Test
    void appliesSelectedItemsAtomicallyAndReplaysTheSameIdempotencyKey() throws Exception {
        TenantGraph graph = createTenantGraph("apply-success");
        postJson(
                "/api/ai/issues/%s/breakdown".formatted(graph.issue().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isOk());

        var suggestion = suggestionRepository.findAll().getFirst();
        UUID idempotencyKey = UUID.randomUUID();
        String applyBody = applyBody(idempotencyKey, null, false);

        postJson(
                "/api/ai/suggestions/%s/apply".formatted(suggestion.getId()),
                applyBody,
                graph.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.createdIssueIds.length()").value(1))
                .andExpect(jsonPath("$.appliedAt").isNotEmpty());

        List<Issue> issuesAfterFirstApply = issueRepository.findAll();
        assertThat(issuesAfterFirstApply).hasSize(2);
        Issue created = issuesAfterFirstApply.stream()
                .filter(issue -> !issue.getId().equals(graph.issue().getId()))
                .findFirst()
                .orElseThrow();
        assertThat(created.getTitle()).isEqualTo("Edited backend task");
        assertThat(created.getDescription())
                .contains("Edited description")
                .contains("Acceptance criteria:")
                .contains("It works");

        postJson(
                "/api/ai/suggestions/%s/apply".formatted(suggestion.getId()),
                applyBody,
                graph.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdIssueIds[0]")
                        .value(created.getId().toString()));

        assertThat(issueRepository.count()).isEqualTo(2);
        assertThat(activityEventRepository.count()).isEqualTo(1);
        assertThat(suggestionRepository.findById(suggestion.getId()))
                .get()
                .satisfies(applied -> {
                    assertThat(applied.getStatus())
                            .isEqualTo(AiSuggestionStatus.APPLIED);
                    assertThat(applied.getApplyIdempotencyKey())
                            .isEqualTo(idempotencyKey);
                    assertThat(applied.getCreatedIssueIds())
                            .containsExactly(created.getId());
                });

        postJson(
                "/api/ai/suggestions/%s/apply".formatted(suggestion.getId()),
                applyBody(UUID.randomUUID(), null, false),
                graph.accessToken()
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_NOT_DRAFT"));
    }

    @Test
    void rollsBackEveryCreatedIssueWhenAnySelectedItemIsInvalid() throws Exception {
        TenantGraph graph = createTenantGraph("apply-rollback");
        postJson(
                "/api/ai/issues/%s/breakdown".formatted(graph.issue().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isOk());

        var suggestion = suggestionRepository.findAll().getFirst();
        postJson(
                "/api/ai/suggestions/%s/apply".formatted(suggestion.getId()),
                applyBody(UUID.randomUUID(), UUID.randomUUID(), true),
                graph.accessToken()
        ).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_SUGGESTION_INVALID"));

        assertThat(issueRepository.findAll())
                .extracting(Issue::getId)
                .containsExactly(graph.issue().getId());
        assertThat(suggestionRepository.findById(suggestion.getId()))
                .get()
                .extracting(com.vokyo.backend.ai.suggestion.AiSuggestion::getStatus)
                .isEqualTo(AiSuggestionStatus.DRAFT);
    }

    @Test
    void concurrentApplyWithTheSameKeyCreatesIssuesOnlyOnce() throws Exception {
        TenantGraph graph = createTenantGraph("apply-concurrent");
        postJson(
                "/api/ai/issues/%s/breakdown".formatted(graph.issue().getId()),
                "{}",
                graph.accessToken()
        ).andExpect(status().isOk());

        var suggestion = suggestionRepository.findAll().getFirst();
        String body = applyBody(UUID.randomUUID(), null, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = List.of(1, 2).stream()
                    .map(ignored -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent apply did not start");
                        }
                        return mockMvc.perform(post(
                                        "/api/ai/suggestions/{suggestionId}/apply",
                                        suggestion.getId()
                                )
                                        .header(
                                                "Authorization",
                                                bearer(graph.accessToken())
                                        )
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var request : requests) {
                assertThat(request.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }

        assertThat(issueRepository.count()).isEqualTo(2);
        assertThat(activityEventRepository.count()).isEqualTo(1);
        assertThat(suggestionRepository.findById(suggestion.getId()))
                .get()
                .extracting(com.vokyo.backend.ai.suggestion.AiSuggestion::getStatus)
                .isEqualTo(AiSuggestionStatus.APPLIED);
    }

    private String applyBody(
            UUID idempotencyKey,
            UUID secondWorkflowStateId,
            boolean selectSecond
    ) {
        String workflowState = secondWorkflowStateId == null
                ? "null"
                : "\"" + secondWorkflowStateId + "\"";
        return """
                {
                  "idempotencyKey": "%s",
                  "items": [
                    {
                      "clientItemId": "item-1",
                      "selected": true,
                      "title": "Edited backend task",
                      "description": "Edited description",
                      "priority": "HIGH",
                      "labelIds": [],
                      "assigneeUserId": null,
                      "workflowStateId": null,
                      "dueDate": null
                    },
                    {
                      "clientItemId": "item-2",
                      "selected": %s,
                      "title": "Edited test task",
                      "description": "Test description",
                      "priority": "MEDIUM",
                      "labelIds": [],
                      "assigneeUserId": null,
                      "workflowStateId": %s,
                      "dueDate": null
                    }
                  ]
                }
                """.formatted(idempotencyKey, selectSecond, workflowState);
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
