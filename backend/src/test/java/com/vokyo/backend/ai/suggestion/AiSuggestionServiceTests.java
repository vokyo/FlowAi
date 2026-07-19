package com.vokyo.backend.ai.suggestion;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSuggestionServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-19T04:00:00Z");

    private RepositoryStub repositoryStub;
    private AiSuggestionRepository repository;
    private AiSuggestionService service;
    private Workspace workspace;
    private Project project;
    private Issue sourceIssue;
    private User user;
    private CurrentWorkspaceContext context;

    @BeforeEach
    void setUp() {
        repositoryStub = new RepositoryStub();
        repository = repositoryStub.repository();

        user = new User("owner@example.com", "password-hash", "Owner");
        workspace = new Workspace(user, "Workspace", "workspace");
        WorkspaceMembership membership = new WorkspaceMembership(
                workspace,
                user,
                WorkspaceRole.OWNER
        );
        project = new Project(workspace, user, "Project", "Description");
        ProjectWorkflowState workflowState = new ProjectWorkflowState(
                workspace,
                project,
                "Todo",
                WorkflowStateCategory.TODO,
                0
        );
        sourceIssue = new Issue(
                workspace,
                project,
                user,
                "Issue",
                "Description",
                null,
                workflowState,
                IssuePriority.MEDIUM,
                null,
                0L
        );

        setId(user, UUID.randomUUID());
        setId(workspace, UUID.randomUUID());
        setId(project, UUID.randomUUID());
        setId(sourceIssue, UUID.randomUUID());

        context = new CurrentWorkspaceContext(user, workspace, membership);
        service = new AiSuggestionService(
                repository,
                properties(Duration.ofDays(7)),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsDraftWithConfiguredTtlAndHashedCanonicalInput() {
        AiSuggestion created = service.createDraft(createCommand(project, sourceIssue));

        AiSuggestion saved = repositoryStub.saved;
        assertThat(created).isSameAs(saved);
        assertThat(saved.getStatus()).isEqualTo(AiSuggestionStatus.DRAFT);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(saved.getInputHash())
                .isEqualTo("f4ca5e3fbd3edaddbf009e6838c40ab66ce5332cfe47bbb4d762aef2ee80e0ba");
        assertThat(saved.getContent().get("overview").asText()).isEqualTo("Plan");
    }

    @Test
    void rejectsCrossWorkspaceProjectBeforeSaving() {
        User otherOwner = new User("other@example.com", "password-hash", "Other");
        Workspace otherWorkspace = new Workspace(otherOwner, "Other", "other");
        Project otherProject = new Project(otherWorkspace, otherOwner, "Other", "Description");
        setId(otherWorkspace, UUID.randomUUID());
        setId(otherProject, UUID.randomUUID());

        assertThatThrownBy(() -> service.createDraft(createCommand(otherProject, null)))
                .isInstanceOfSatisfying(AiFeatureException.class, exception ->
                        assertThat(exception.code()).isEqualTo("AI_SUGGESTION_INVALID"));
        assertThat(repositoryStub.saved).isNull();
    }

    @Test
    void returnsNotFoundWithoutRevealingAnotherUsersSuggestion() {
        UUID suggestionId = UUID.randomUUID();
        repositoryStub.lockedResult = Optional.empty();

        assertThatThrownBy(() -> service.getOwnedSuggestion(context, suggestionId))
                .isInstanceOfSatisfying(AiFeatureException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("AI_SUGGESTION_NOT_FOUND");
                    assertThat(exception.status().value()).isEqualTo(404);
                });
        assertThat(repositoryStub.lastWorkspaceId).isEqualTo(workspace.getId());
        assertThat(repositoryStub.lastUserId).isEqualTo(user.getId());
        assertThat(repositoryStub.lastSuggestionId).isEqualTo(suggestionId);
    }

    @Test
    void dismissesActiveDraftAndExpiresElapsedDraft() {
        UUID activeId = UUID.randomUUID();
        AiSuggestion active = suggestionExpiringAt(NOW.plusSeconds(60));
        repositoryStub.lockedResult = Optional.of(active);

        assertThat(service.dismiss(context, activeId).getStatus())
                .isEqualTo(AiSuggestionStatus.DISMISSED);

        UUID expiredId = UUID.randomUUID();
        AiSuggestion expired = suggestionExpiringAt(NOW);
        repositoryStub.lockedResult = Optional.of(expired);

        assertThat(service.getOwnedSuggestion(context, expiredId).getStatus())
                .isEqualTo(AiSuggestionStatus.EXPIRED);
    }

    @Test
    void rejectsNonPositiveSuggestionTtl() {
        service = new AiSuggestionService(
                repository,
                properties(Duration.ZERO),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.createDraft(createCommand(project, sourceIssue)))
                .isInstanceOfSatisfying(AiFeatureException.class, exception ->
                        assertThat(exception.code()).isEqualTo("AI_SUGGESTION_INVALID"));
    }

    private AiSuggestionService.CreateDraftCommand createCommand(
            Project commandProject,
            Issue commandIssue
    ) {
        return new AiSuggestionService.CreateDraftCommand(
                context,
                commandProject,
                commandIssue,
                AiSuggestionType.ISSUE_BREAKDOWN,
                JsonNodeFactory.instance.objectNode().put("overview", "Plan"),
                "issue-breakdown-v1",
                "fake",
                "fake-model",
                "canonical-input",
                10,
                20
        );
    }

    private AiSuggestion suggestionExpiringAt(Instant expiresAt) {
        return new AiSuggestion(
                workspace,
                project,
                sourceIssue,
                user,
                AiSuggestionType.ISSUE_BREAKDOWN,
                JsonNodeFactory.instance.objectNode().put("overview", "Plan"),
                "issue-breakdown-v1",
                "fake",
                "fake-model",
                "a".repeat(64),
                10,
                20,
                expiresAt
        );
    }

    private AiProperties properties(Duration ttl) {
        return new AiProperties(
                true,
                ttl,
                Duration.ofSeconds(30),
                8,
                20,
                20,
                100
        );
    }

    private void setId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    private static final class RepositoryStub implements InvocationHandler {
        private AiSuggestion saved;
        private Optional<AiSuggestion> lockedResult = Optional.empty();
        private UUID lastWorkspaceId;
        private UUID lastUserId;
        private UUID lastSuggestionId;

        private AiSuggestionRepository repository() {
            return (AiSuggestionRepository) Proxy.newProxyInstance(
                    AiSuggestionRepository.class.getClassLoader(),
                    new Class<?>[]{AiSuggestionRepository.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "save" -> {
                    saved = (AiSuggestion) args[0];
                    yield saved;
                }
                case "findOwnedByIdForUpdate" -> {
                    lastWorkspaceId = (UUID) args[0];
                    lastUserId = (UUID) args[1];
                    lastSuggestionId = (UUID) args[2];
                    yield lockedResult;
                }
                case "toString" -> "AiSuggestionRepositoryStub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
