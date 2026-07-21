package com.vokyo.backend.ai.breakdown;

import com.vokyo.backend.activity.ActivityEvent;
import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.activity.ActivityEventType;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownRequest;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueComment;
import com.vokyo.backend.issue.IssueCommentRepository;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueBreakdownContextBuilderTests {

    @Test
    void buildsTenantScopedPromptSafeContextWithBoundedHistoryAndCandidates() {
        Fixture fixture = fixture();
        TestHarness harness = harness(fixture, properties(6, 2, 2));

        IssueComment newestComment = comment(fixture, "newest comment", "2026-07-21T03:00:00Z");
        IssueComment middleComment = comment(fixture, "middle comment", "2026-07-21T02:00:00Z");
        IssueComment oldestComment = comment(fixture, "oldest comment", "2026-07-21T01:00:00Z");
        harness.comments.on("findFirstPage", arguments ->
                List.of(newestComment, middleComment, oldestComment));

        ActivityEvent newestActivity = activity(fixture, ActivityEventType.ISSUE_PRIORITY_CHANGED,
                "2026-07-21T03:30:00Z", Map.of("toPriority", "HIGH"));
        ActivityEvent middleActivity = activity(fixture, ActivityEventType.ISSUE_TITLE_CHANGED,
                "2026-07-21T02:30:00Z", Map.of("toTitle", "Plan release"));
        ActivityEvent oldestActivity = activity(fixture, ActivityEventType.ISSUE_CREATED,
                "2026-07-21T01:30:00Z", Map.of("issueId", fixture.issueId.toString()));
        harness.activities.on("findFirstPage", arguments ->
                List.of(newestActivity, middleActivity, oldestActivity));

        ProjectMember activeMember = new ProjectMember(
                fixture.workspace,
                fixture.project,
                fixture.user,
                ProjectRole.OWNER
        );
        ProjectMember disabledMember = new ProjectMember(
                fixture.workspace,
                fixture.project,
                fixture.disabledUser,
                ProjectRole.MEMBER
        );
        disabledMember.disable();
        harness.labels.returns(
                "findByWorkspace_IdAndProject_IdOrderByNameAsc",
                List.of(fixture.label)
        );
        harness.members.returns("findByProjectForMemberList", List.of(activeMember, disabledMember));
        harness.workflowStates.returns(
                "findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc",
                List.of(fixture.workflowState)
        );

        BuiltIssueBreakdownContext built = harness.builder.build(
                fixture.jwt,
                fixture.issueId,
                new IssueBreakdownRequest("  Split by delivery milestone  ", 4, true, true)
        );

        IssueBreakdownContext context = built.modelContext();
        assertThat(built.currentContext()).isSameAs(fixture.currentContext);
        assertThat(built.project()).isSameAs(fixture.project);
        assertThat(built.sourceIssue()).isSameAs(fixture.issue);
        assertThat(context.instruction()).isEqualTo("Split by delivery milestone");
        assertThat(context.maxItems()).isEqualTo(4);
        assertThat(context.project().name()).isEqualTo("Flow project");
        assertThat(context.issue().id()).isEqualTo(fixture.issueId);
        assertThat(context.issue().status()).isEqualTo("TODO");
        assertThat(context.issue().priority()).isEqualTo("HIGH");
        assertThat(context.issue().workflowState().id()).isEqualTo(fixture.workflowStateId);
        assertThat(context.issue().assignee().userId()).isEqualTo(fixture.userId);
        assertThat(context.issue().labelIds()).containsExactly(fixture.labelId);

        assertThat(context.comments())
                .extracting(IssueBreakdownContext.CommentContext::body)
                .containsExactly("middle comment", "newest comment");
        assertThat(context.activities())
                .extracting(IssueBreakdownContext.ActivityContext::eventType)
                .containsExactly("ISSUE_TITLE_CHANGED", "ISSUE_PRIORITY_CHANGED");
        assertThat(context.activities().getFirst().actorName()).isEqualTo("Owner");
        assertThat(context.sourceStats()).isEqualTo(
                new IssueBreakdownContext.SourceStats(2, 2, true, true, true)
        );
        assertThat(context.allowedCandidates().labels()).containsExactly(
                new IssueBreakdownContext.LabelCandidate(fixture.labelId, "Backend", "#112233")
        );
        assertThat(context.allowedCandidates().members()).containsExactly(
                new IssueBreakdownContext.MemberCandidate(fixture.userId, "Owner", "OWNER")
        );
        assertThat(context.allowedCandidates().workflowStates()).containsExactly(
                new IssueBreakdownContext.WorkflowStateCandidate(fixture.workflowStateId, "Todo", "TODO")
        );
        assertThatThrownBy(() -> context.activities().getFirst().metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertScopedHistoryQuery(harness.comments, fixture);
        assertScopedHistoryQuery(harness.activities, fixture);
        assertThat(harness.issues.arguments("findByIdAndWorkspace_Id"))
                .containsExactly(fixture.issueId, fixture.workspaceId);
        assertThat(harness.projectAccess.checkedIssue).isSameAs(fixture.issue);
        assertThat(harness.projectAccess.checkedContext).isSameAs(fixture.currentContext);
    }

    @Test
    void skipsOptionalHistoryQueriesAndUsesConfiguredDefault() {
        Fixture fixture = fixture();
        TestHarness harness = harness(fixture, properties(6, 2, 2));
        stubEmptyCandidates(harness);

        IssueBreakdownContext context = harness.builder.build(
                fixture.jwt,
                fixture.issueId,
                new IssueBreakdownRequest("   ", null, false, false)
        ).modelContext();

        assertThat(context.instruction()).isNull();
        assertThat(context.maxItems()).isEqualTo(5);
        assertThat(context.comments()).isEmpty();
        assertThat(context.activities()).isEmpty();
        assertThat(context.sourceStats()).isEqualTo(
                new IssueBreakdownContext.SourceStats(0, 0, false, false, false)
        );
        assertThat(harness.comments.calls("findFirstPage")).isZero();
        assertThat(harness.activities.calls("findFirstPage")).isZero();
    }

    @Test
    void rejectsMaxItemsAboveTheConfiguredMaximumBeforeLoadingContextData() {
        Fixture fixture = fixture();
        TestHarness harness = harness(fixture, properties(3, 2, 2));

        assertThatThrownBy(() -> harness.builder.build(
                fixture.jwt,
                fixture.issueId,
                new IssueBreakdownRequest(null, 4, false, false)
        ))
                .isInstanceOfSatisfying(AiFeatureException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("AI_REQUEST_INVALID");
                    assertThat(exception.getMessage()).contains("configured maximum of 3");
                });

        assertThat(harness.comments.calls("findFirstPage")).isZero();
        assertThat(harness.activities.calls("findFirstPage")).isZero();
        assertThat(harness.labels.calls("findByWorkspace_IdAndProject_IdOrderByNameAsc")).isZero();
        assertThat(harness.members.calls("findByProjectForMemberList")).isZero();
        assertThat(harness.workflowStates.calls(
                "findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc"
        )).isZero();
    }

    @Test
    void stopsBeforeLoadingContextWhenProjectAccessIsDenied() {
        Fixture fixture = fixture();
        TestHarness harness = harness(fixture, properties(6, 2, 2));
        harness.projectAccess.deny = true;

        assertThatThrownBy(() -> harness.builder.build(
                fixture.jwt,
                fixture.issueId,
                new IssueBreakdownRequest(null, null, true, true)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(harness.comments.calls("findFirstPage")).isZero();
        assertThat(harness.activities.calls("findFirstPage")).isZero();
        assertThat(harness.labels.calls("findByWorkspace_IdAndProject_IdOrderByNameAsc")).isZero();
    }

    private TestHarness harness(Fixture fixture, AiProperties properties) {
        RepositoryStub<IssueRepository> issues = new RepositoryStub<>(IssueRepository.class);
        RepositoryStub<IssueCommentRepository> comments = new RepositoryStub<>(IssueCommentRepository.class);
        RepositoryStub<ActivityEventRepository> activities = new RepositoryStub<>(ActivityEventRepository.class);
        RepositoryStub<ProjectLabelRepository> labels = new RepositoryStub<>(ProjectLabelRepository.class);
        RepositoryStub<ProjectMemberRepository> members = new RepositoryStub<>(ProjectMemberRepository.class);
        RepositoryStub<ProjectWorkflowStateRepository> workflowStates =
                new RepositoryStub<>(ProjectWorkflowStateRepository.class);
        issues.returns("findByIdAndWorkspace_Id", Optional.of(fixture.issue));

        StubWorkspaceAccessService workspaceAccess = new StubWorkspaceAccessService(fixture.currentContext);
        StubProjectAccessService projectAccess = new StubProjectAccessService();
        IssueBreakdownContextBuilder builder = new IssueBreakdownContextBuilder(
                workspaceAccess,
                projectAccess,
                issues.proxy(),
                comments.proxy(),
                activities.proxy(),
                labels.proxy(),
                members.proxy(),
                workflowStates.proxy(),
                properties
        );
        return new TestHarness(
                builder,
                projectAccess,
                issues,
                comments,
                activities,
                labels,
                members,
                workflowStates
        );
    }

    private void stubEmptyCandidates(TestHarness harness) {
        harness.labels.returns("findByWorkspace_IdAndProject_IdOrderByNameAsc", List.of());
        harness.members.returns("findByProjectForMemberList", List.of());
        harness.workflowStates.returns(
                "findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc",
                List.of()
        );
    }

    private void assertScopedHistoryQuery(RepositoryStub<?> repository, Fixture fixture) {
        Object[] arguments = repository.arguments("findFirstPage");
        assertThat(arguments[0]).isEqualTo(fixture.workspaceId);
        assertThat(arguments[1]).isEqualTo(fixture.projectId);
        assertThat(arguments[2]).isEqualTo(fixture.issueId);
        assertThat(((Pageable) arguments[3]).getPageSize()).isEqualTo(3);
    }

    private AiProperties properties(int maxItems, int commentsLimit, int activityLimit) {
        return new AiProperties(
                true,
                Duration.ofDays(7),
                Duration.ofSeconds(30),
                maxItems,
                commentsLimit,
                activityLimit,
                100
        );
    }

    private IssueComment comment(Fixture fixture, String body, String createdAt) {
        IssueComment comment = new IssueComment(
                fixture.workspace,
                fixture.project,
                fixture.issue,
                fixture.user,
                body
        );
        ReflectionTestUtils.setField(comment, "createdAt", Instant.parse(createdAt));
        return comment;
    }

    private ActivityEvent activity(
            Fixture fixture,
            ActivityEventType type,
            String createdAt,
            Map<String, Object> metadata
    ) {
        ActivityEvent event = new ActivityEvent(
                fixture.workspace,
                fixture.project,
                fixture.issue,
                fixture.user,
                type,
                metadata
        );
        ReflectionTestUtils.setField(event, "createdAt", Instant.parse(createdAt));
        return event;
    }

    private Fixture fixture() {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID workflowStateId = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();

        User user = new User("owner@example.com", "hash", "Owner");
        ReflectionTestUtils.setField(user, "id", userId);
        User disabledUser = new User("disabled@example.com", "hash", "Disabled");
        ReflectionTestUtils.setField(disabledUser, "id", UUID.randomUUID());
        Workspace workspace = new Workspace(user, "Workspace", "workspace");
        ReflectionTestUtils.setField(workspace, "id", workspaceId);
        WorkspaceMembership membership = new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER);
        CurrentWorkspaceContext currentContext = new CurrentWorkspaceContext(user, workspace, membership);
        Project project = new Project(workspace, user, "Flow project", "Project context");
        ReflectionTestUtils.setField(project, "id", projectId);
        ProjectWorkflowState workflowState = new ProjectWorkflowState(
                workspace,
                project,
                "Todo",
                WorkflowStateCategory.TODO,
                0
        );
        ReflectionTestUtils.setField(workflowState, "id", workflowStateId);
        ProjectLabel label = new ProjectLabel(workspace, project, "Backend", "#112233");
        ReflectionTestUtils.setField(label, "id", labelId);
        Issue issue = new Issue(
                workspace,
                project,
                user,
                "Plan release",
                "Break the release into deliverable work",
                user,
                workflowState,
                IssuePriority.HIGH,
                LocalDate.of(2026, 8, 1),
                1
        );
        ReflectionTestUtils.setField(issue, "id", issueId);
        issue.replaceLabels(List.of(label));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .issuedAt(Instant.parse("2026-07-21T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-22T00:00:00Z"))
                .build();

        return new Fixture(
                userId,
                workspaceId,
                projectId,
                issueId,
                workflowStateId,
                labelId,
                user,
                disabledUser,
                workspace,
                currentContext,
                project,
                workflowState,
                label,
                issue,
                jwt
        );
    }

    private static final class StubWorkspaceAccessService extends WorkspaceAccessService {
        private final CurrentWorkspaceContext context;

        private StubWorkspaceAccessService(CurrentWorkspaceContext context) {
            super(null, null);
            this.context = context;
        }

        @Override
        public CurrentWorkspaceContext requireCurrentContext(Jwt jwt) {
            return context;
        }
    }

    private static final class StubProjectAccessService extends ProjectAccessService {
        private boolean deny;
        private Issue checkedIssue;
        private CurrentWorkspaceContext checkedContext;

        private StubProjectAccessService() {
            super(null, null);
        }

        @Override
        public void requireIssueProjectAccess(Issue issue, CurrentWorkspaceContext context) {
            checkedIssue = issue;
            checkedContext = context;
            if (deny) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found");
            }
        }
    }

    private static final class RepositoryStub<T> implements InvocationHandler {
        private final Class<T> repositoryType;
        private final Map<String, Function<Object[], Object>> handlers = new HashMap<>();
        private final Map<String, Integer> callCounts = new HashMap<>();
        private final Map<String, Object[]> lastArguments = new HashMap<>();

        private RepositoryStub(Class<T> repositoryType) {
            this.repositoryType = repositoryType;
        }

        private T proxy() {
            return repositoryType.cast(Proxy.newProxyInstance(
                    repositoryType.getClassLoader(),
                    new Class<?>[]{repositoryType},
                    this
            ));
        }

        private void returns(String methodName, Object result) {
            on(methodName, arguments -> result);
        }

        private void on(String methodName, Function<Object[], Object> handler) {
            handlers.put(methodName, handler);
        }

        private int calls(String methodName) {
            return callCounts.getOrDefault(methodName, 0);
        }

        private Object[] arguments(String methodName) {
            return lastArguments.get(methodName);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> repositoryType.getSimpleName() + "Stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                };
            }

            String methodName = method.getName();
            Object[] safeArguments = arguments == null ? new Object[0] : arguments.clone();
            callCounts.merge(methodName, 1, Integer::sum);
            lastArguments.put(methodName, safeArguments);
            Function<Object[], Object> handler = handlers.get(methodName);
            if (handler != null) {
                return handler.apply(safeArguments);
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType() == long.class) {
                return 0L;
            }
            if (method.getReturnType() == int.class) {
                return 0;
            }
            return null;
        }
    }

    private record TestHarness(
            IssueBreakdownContextBuilder builder,
            StubProjectAccessService projectAccess,
            RepositoryStub<IssueRepository> issues,
            RepositoryStub<IssueCommentRepository> comments,
            RepositoryStub<ActivityEventRepository> activities,
            RepositoryStub<ProjectLabelRepository> labels,
            RepositoryStub<ProjectMemberRepository> members,
            RepositoryStub<ProjectWorkflowStateRepository> workflowStates
    ) {
    }

    private record Fixture(
            UUID userId,
            UUID workspaceId,
            UUID projectId,
            UUID issueId,
            UUID workflowStateId,
            UUID labelId,
            User user,
            User disabledUser,
            Workspace workspace,
            CurrentWorkspaceContext currentContext,
            Project project,
            ProjectWorkflowState workflowState,
            ProjectLabel label,
            Issue issue,
            Jwt jwt
    ) {
    }
}
