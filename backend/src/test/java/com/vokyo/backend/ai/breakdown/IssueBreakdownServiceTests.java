package com.vokyo.backend.ai.breakdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGeneration;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.ai.AiModelOutputException;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownRequest;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownSuggestionResponse;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionService;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueBreakdownServiceTests {

    @Test
    void returnsProviderUnavailableBeforeLoadingBusinessContext() {
        Fixture fixture = new Fixture(null);

        assertThatThrownBy(() -> fixture.service.generate(
                jwt(),
                UUID.randomUUID(),
                new IssueBreakdownRequest(null, null, false, false)
        )).isInstanceOf(AiFeatureException.class)
                .satisfies(exception -> assertThat(((AiFeatureException) exception).code())
                        .isEqualTo("AI_PROVIDER_UNAVAILABLE"));

        assertThat(fixture.contextBuilder.calls).isZero();
        assertThat(fixture.registry.get("flowai.ai.requests")
                .tag("feature", "issue_breakdown")
                .tag("result", "provider_unavailable")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void persistsValidatedDraftAndReturnsPublicSuggestionContract() {
        SequenceGateway gateway = new SequenceGateway(generation(validResult(), 12, 7));
        Fixture fixture = new Fixture(gateway);

        IssueBreakdownSuggestionResponse response = fixture.service.generate(
                jwt(),
                fixture.issueId,
                new IssueBreakdownRequest("MVP only", 4, true, false)
        );

        assertThat(response.id()).isEqualTo(fixture.suggestionId);
        assertThat(response.type()).isEqualTo(AiSuggestionType.ISSUE_BREAKDOWN);
        assertThat(response.status()).isEqualTo(AiSuggestionStatus.DRAFT);
        assertThat(response.projectId()).isEqualTo(fixture.projectId);
        assertThat(response.sourceIssueId()).isEqualTo(fixture.issueId);
        assertThat(response.content().items()).hasSize(2);
        assertThat(response.metadata().promptVersion()).isEqualTo("issue-breakdown-v1");
        assertThat(response.metadata().contextTruncated()).isTrue();

        AiSuggestionService.CreateDraftCommand command = fixture.suggestionService.command;
        assertThat(command.type()).isEqualTo(AiSuggestionType.ISSUE_BREAKDOWN);
        assertThat(command.provider()).isEqualTo("fake");
        assertThat(command.model()).isEqualTo("fake-model");
        assertThat(command.inputTokens()).isEqualTo(12);
        assertThat(command.outputTokens()).isEqualTo(7);
        assertThat(command.content().get("overview").asText()).isEqualTo("Delivery plan");
        assertThat(command.canonicalInput())
                .contains("issue-breakdown-v1", "trusted generation", "Source issue");
        assertThat(fixture.registry.get("flowai.ai.suggestions")
                .tag("type", "issue_breakdown")
                .tag("status", "draft")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void repairsOneInvalidResultAndAggregatesTokenUsage() {
        SequenceGateway gateway = new SequenceGateway(
                generation(oneItemResult(), 3, 4),
                generation(validResult(), 5, 6)
        );
        Fixture fixture = new Fixture(gateway);

        fixture.service.generate(
                jwt(),
                fixture.issueId,
                new IssueBreakdownRequest(null, 4, false, false)
        );

        assertThat(gateway.calls).isEqualTo(2);
        assertThat(gateway.systemPrompts).containsExactly("trusted generation", "trusted repair");
        assertThat(gateway.userPrompts.get(1))
                .contains("invalidOutput", "validationError", "between 2 and 4 items")
                .doesNotContain("Source issue");
        assertThat(fixture.suggestionService.command.inputTokens()).isEqualTo(8);
        assertThat(fixture.suggestionService.command.outputTokens()).isEqualTo(10);
        assertThat(fixture.registry.get("flowai.ai.tokens")
                .tag("direction", "input")
                .tag("feature", "issue_breakdown")
                .tag("model", "fake-model")
                .counter().count()).isEqualTo(8);
        assertThat(fixture.registry.get("flowai.ai.tokens")
                .tag("direction", "output")
                .tag("feature", "issue_breakdown")
                .tag("model", "fake-model")
                .counter().count()).isEqualTo(10);
    }

    @Test
    void returnsInvalidResponseAfterTheSingleRepairAlsoFails() {
        AiModelOutputException invalid = new AiModelOutputException(
                "Schema mismatch", "not-json", "fake", "fake-model", 2, 1, null
        );
        SequenceGateway gateway = new SequenceGateway(invalid, invalid);
        Fixture fixture = new Fixture(gateway);

        assertThatThrownBy(() -> fixture.service.generate(
                jwt(),
                fixture.issueId,
                new IssueBreakdownRequest(null, 4, false, false)
        )).isInstanceOf(AiFeatureException.class)
                .satisfies(exception -> assertThat(((AiFeatureException) exception).code())
                        .isEqualTo("AI_INVALID_RESPONSE"));

        assertThat(gateway.calls).isEqualTo(2);
        assertThat(fixture.suggestionService.calls).isZero();
    }

    private static AiGeneration<IssueBreakdownResult> generation(
            IssueBreakdownResult result,
            int inputTokens,
            int outputTokens
    ) {
        return new AiGeneration<>(
                result,
                "{\"candidate\":true}",
                "fake",
                "fake-model",
                inputTokens,
                outputTokens
        );
    }

    private static IssueBreakdownResult oneItemResult() {
        return new IssueBreakdownResult("Too small", List.of(item("item-1", List.of())), List.of());
    }

    private static IssueBreakdownResult validResult() {
        return new IssueBreakdownResult(
                "Delivery plan",
                List.of(
                        item("item-1", List.of()),
                        item("item-2", List.of("item-1"))
                ),
                List.of()
        );
    }

    private static IssueBreakdownResult.Item item(String id, List<String> dependencies) {
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

    private static Jwt jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }

    private static final class Fixture {
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final UUID projectId = UUID.randomUUID();
        private final UUID issueId = UUID.randomUUID();
        private final UUID suggestionId = UUID.randomUUID();
        private final Instant createdAt = Instant.parse("2026-07-21T01:00:00Z");
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final FakeContextBuilder contextBuilder;
        private final FakeSuggestionService suggestionService;
        private final IssueBreakdownService service;

        private Fixture(AiModelGateway gateway) {
            User user = new User("test@example.com", "hash", "Test User");
            Workspace workspace = new Workspace(user, "Workspace", "workspace");
            Project project = new Project(workspace, user, "Project", "Description");
            ProjectWorkflowState workflowState = new ProjectWorkflowState(
                    workspace, project, "Todo", WorkflowStateCategory.TODO, 10_000
            );
            Issue issue = new Issue(
                    workspace,
                    project,
                    user,
                    "Source issue",
                    "Source description",
                    null,
                    workflowState,
                    IssuePriority.HIGH,
                    null,
                    10_000
            );
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(workspace, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(project, "id", projectId);
            ReflectionTestUtils.setField(workflowState, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(issue, "id", issueId);

            IssueBreakdownContext context = context(issueId, workflowState.getId());
            CurrentWorkspaceContext currentContext = new CurrentWorkspaceContext(user, workspace, null);
            contextBuilder = new FakeContextBuilder(
                    new BuiltIssueBreakdownContext(currentContext, project, issue, context)
            );

            AiSuggestion suggestion = new AiSuggestion(
                    workspace,
                    project,
                    issue,
                    user,
                    AiSuggestionType.ISSUE_BREAKDOWN,
                    objectMapper.createObjectNode().put("overview", "Delivery plan"),
                    "issue-breakdown-v1",
                    "fake",
                    "fake-model",
                    "a".repeat(64),
                    12,
                    7,
                    createdAt.plusSeconds(604_800)
            );
            ReflectionTestUtils.setField(suggestion, "id", suggestionId);
            ReflectionTestUtils.setField(suggestion, "createdAt", createdAt);
            ReflectionTestUtils.setField(suggestion, "updatedAt", createdAt);
            suggestionService = new FakeSuggestionService(suggestion);

            IssueBreakdownPromptFactory promptFactory = new IssueBreakdownPromptFactory(
                    objectMapper,
                    resource("trusted generation"),
                    resource("trusted repair")
            );
            service = new IssueBreakdownService(
                    contextBuilder,
                    promptFactory,
                    provider(gateway),
                    new IssueBreakdownValidator(),
                    suggestionService,
                    objectMapper,
                    new IssueBreakdownMetrics(registry)
            );
        }

        private IssueBreakdownContext context(UUID sourceIssueId, UUID stateId) {
            return new IssueBreakdownContext(
                    new IssueBreakdownContext.ProjectContext("Project", "Description"),
                    new IssueBreakdownContext.SourceIssueContext(
                            sourceIssueId,
                            "Source issue",
                            "Description",
                            "TODO",
                            "HIGH",
                            null,
                            new IssueBreakdownContext.WorkflowStateContext(stateId, "Todo", "TODO"),
                            null,
                            List.of()
                    ),
                    null,
                    4,
                    List.of(),
                    List.of(),
                    new IssueBreakdownContext.AllowedCandidates(List.of(), List.of(), List.of()),
                    new IssueBreakdownContext.SourceStats(0, 0, false, false, true)
            );
        }
    }

    private static ByteArrayResource resource(String text) {
        return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectProvider<AiModelGateway> provider(AiModelGateway gateway) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (gateway != null) {
            beanFactory.addBean("gateway", gateway);
        }
        return beanFactory.getBeanProvider(AiModelGateway.class);
    }

    private static final class FakeContextBuilder extends IssueBreakdownContextBuilder {
        private final BuiltIssueBreakdownContext built;
        private int calls;

        private FakeContextBuilder(BuiltIssueBreakdownContext built) {
            super(null, null, null, null, null, null, null, null, null);
            this.built = built;
        }

        @Override
        public BuiltIssueBreakdownContext build(Jwt jwt, UUID issueId, IssueBreakdownRequest request) {
            calls++;
            return built;
        }
    }

    private static final class FakeSuggestionService extends AiSuggestionService {
        private final AiSuggestion suggestion;
        private CreateDraftCommand command;
        private int calls;

        private FakeSuggestionService(AiSuggestion suggestion) {
            super(null, null, null);
            this.suggestion = suggestion;
        }

        @Override
        public AiSuggestion createDraft(CreateDraftCommand command) {
            calls++;
            this.command = command;
            return suggestion;
        }
    }

    private static final class SequenceGateway implements AiModelGateway {
        private final ArrayDeque<Object> responses = new ArrayDeque<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private final List<String> userPrompts = new ArrayList<>();
        private int calls;

        private SequenceGateway(Object... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> AiGeneration<T> generate(String systemPrompt, String userPrompt, Class<T> responseType) {
            calls++;
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            Object response = responses.removeFirst();
            if (response instanceof RuntimeException exception) {
                throw exception;
            }
            return (AiGeneration<T>) response;
        }
    }
}
