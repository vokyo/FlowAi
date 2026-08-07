package com.vokyo.backend.integration;

import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.analytics.AnalyticsService;
import com.vokyo.backend.analytics.dto.AnalyticsCompletionTrendPointResponse;
import com.vokyo.backend.analytics.dto.AnalyticsOverviewResponse;
import com.vokyo.backend.auth.AuthService;
import com.vokyo.backend.auth.dto.LoginRequest;
import com.vokyo.backend.demo.DemoDataSeeder;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueQueryService;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.issue.dto.IssueListItemResponse;
import com.vokyo.backend.pagination.CursorPage;
import com.vokyo.backend.project.ProjectQueryService;
import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectMemberResponse;
import com.vokyo.backend.project.dto.ProjectResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.issue.IssueCommentRepository;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the demo seeder against a clean PostgreSQL 17 container that Flyway has
 * just migrated, and checks the dataset the public deployment depends on.
 *
 * <p>This is the guard against the seeder rotting as the schema moves. It fails
 * if the seeder stops writing, if it duplicates on a second run, or if any of the
 * things the README claims — a paginated list, filters with something to filter
 * on, a completion trend with more than one point, a saved Copilot draft — stops
 * being true of the seeded data.
 *
 * <p>The counts are the dataset in {@code DemoDataset}; changing a list there
 * changes a number here.
 */
@SpringBootTest(properties = {
        "app.demo.enabled=true",
        "app.demo.email=demo@flowai.dev",
        "app.demo.password=demo1234",
        "app.demo.workspace-name=Northwind Labs",
        "app.ai.enabled=false"
})
@Import(TestcontainersConfiguration.class)
class DemoSeedIntegrationTests {

    private static final String DEMO_EMAIL = "demo@flowai.dev";
    private static final String DEMO_PASSWORD = "demo1234";
    private static final String DEMO_WORKSPACE = "Northwind Labs";

    private static final int EXPECTED_PROJECTS = 2;
    private static final int EXPECTED_ISSUES = 74;
    private static final int EXPECTED_WEB_PLATFORM_ISSUES = 56;
    private static final int EXPECTED_COMMENTS = 16;
    private static final int ISSUE_PAGE_LIMIT = 50;

    @Autowired
    private DemoDataSeeder demoDataSeeder;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ProjectQueryService projectQueryService;

    @Autowired
    private IssueQueryService issueQueryService;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueCommentRepository issueCommentRepository;

    @Autowired
    private AiSuggestionRepository aiSuggestionRepository;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Test
    void seedsTheDemoWorkspaceOnAFreshDatabase() {
        List<Workspace> workspaces = workspaceRepository.findAll();

        assertThat(workspaces)
                .extracting(Workspace::getName)
                .containsExactly(DEMO_WORKSPACE);
        assertThat(workspaces.get(0).getOwner().getId())
                .isEqualTo(userRepository.findByEmail(DEMO_EMAIL).orElseThrow().getId());
        assertThat(issueRepository.count()).isEqualTo(EXPECTED_ISSUES);
        assertThat(issueCommentRepository.count()).isEqualTo(EXPECTED_COMMENTS);
        assertThat(projectQueryService.listProjects(signIn(), false)).hasSize(EXPECTED_PROJECTS);
    }

    @Test
    void seedingASecondTimeChangesNothing() {
        long issuesBefore = issueRepository.count();
        long commentsBefore = issueCommentRepository.count();
        long activityBefore = activityEventRepository.count();
        long suggestionsBefore = aiSuggestionRepository.count();

        assertThat(demoDataSeeder.seed())
                .isEqualTo(DemoDataSeeder.DemoSeedOutcome.ALREADY_PRESENT);

        assertThat(issueRepository.count()).isEqualTo(issuesBefore);
        assertThat(issueCommentRepository.count()).isEqualTo(commentsBefore);
        assertThat(activityEventRepository.count()).isEqualTo(activityBefore);
        assertThat(aiSuggestionRepository.count()).isEqualTo(suggestionsBefore);
        assertThat(workspaceRepository.count()).isEqualTo(1);
    }

    @Test
    void theIssueListPaginatesToASecondPage() {
        Jwt jwt = signIn();
        UUID projectId = webPlatform(jwt).id();

        CursorPage<IssueListItemResponse> firstPage = listIssues(jwt, projectId, null, null, null, null);

        assertThat(firstPage.items()).hasSize(ISSUE_PAGE_LIMIT);
        assertThat(firstPage.nextCursor()).isNotNull();

        CursorPage<IssueListItemResponse> secondPage = issueQueryService.listIssues(
                jwt, projectId, null, null, null, null, null, null,
                firstPage.nextCursor(), ISSUE_PAGE_LIMIT
        );

        assertThat(secondPage.items())
                .hasSize(EXPECTED_WEB_PLATFORM_ISSUES - ISSUE_PAGE_LIMIT);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(secondPage.items())
                .extracting(IssueListItemResponse::id)
                .doesNotContainAnyElementsOf(
                        firstPage.items().stream().map(IssueListItemResponse::id).toList()
                );
    }

    @Test
    void filteringByAssigneeLabelAndPriorityAllReturnResults() {
        Jwt jwt = signIn();
        UUID projectId = webPlatform(jwt).id();

        UUID assigneeUserId = projectQueryService.listProjectMembers(jwt, projectId).stream()
                .filter(member -> !member.email().equals(DEMO_EMAIL))
                .map(ProjectMemberResponse::userId)
                .findFirst()
                .orElseThrow();
        UUID labelId = projectQueryService.listProjectLabels(jwt, projectId).stream()
                .filter(label -> label.name().equals("bug"))
                .map(ProjectLabelResponse::id)
                .findFirst()
                .orElseThrow();

        assertThat(listIssues(jwt, projectId, null, assigneeUserId, null, null).items())
                .as("issues assigned to a teammate")
                .hasSizeGreaterThan(1);
        assertThat(listIssues(jwt, projectId, null, null, labelId, null).items())
                .as("issues carrying the bug label")
                .hasSizeGreaterThan(1);
        assertThat(listIssues(jwt, projectId, IssuePriority.HIGH, null, null, null).items())
                .as("high priority issues")
                .hasSizeGreaterThan(1);
        assertThat(listIssues(jwt, projectId, null, null, null, "checkout").items())
                .as("full text search")
                .isNotEmpty();
    }

    @Test
    void everyWorkflowStateOnTheBoardHoldsIssues() {
        Jwt jwt = signIn();
        UUID projectId = webPlatform(jwt).id();
        List<ProjectWorkflowStateResponse> states =
                projectQueryService.listProjectWorkflowStates(jwt, projectId);

        assertThat(states).hasSize(5);
        for (ProjectWorkflowStateResponse state : states) {
            assertThat(listIssues(jwt, projectId, null, null, null, null, state.id()).items())
                    .as("issues in the %s column", state.name())
                    .isNotEmpty();
        }
    }

    @Test
    void theCompletionTrendHasManyPointsRatherThanOne() {
        Jwt jwt = signIn();
        AnalyticsOverviewResponse overview = analyticsService.getOverview(
                jwt,
                webPlatform(jwt).id(),
                30
        );

        List<AnalyticsCompletionTrendPointResponse> completedDays = overview.completionTrend()
                .stream()
                .filter(point -> point.count() > 0)
                .toList();

        /*
         * Eight completion days fall inside the range, and the weekend shift can
         * merge at most three of them onto a neighbouring Friday. Four is the
         * floor that holds whatever weekday seeding runs on; the point of the
         * assertion is that the chart never collapses towards a single bar.
         */
        assertThat(completedDays)
                .as("distinct days with a completion inside the default 30 day range")
                .hasSizeGreaterThanOrEqualTo(4);
        assertThat(completedDays)
                .as("a chart of identical bars reads as generated; some day must carry more than one")
                .anySatisfy(point -> assertThat(point.count()).isGreaterThan(1L));
        assertThat(completedDays)
                .as("and some day inside the range must carry none, or the bars have no gaps")
                .hasSizeLessThan(overview.completionTrend().size());
        assertThat(overview.completionTrend())
                .filteredOn(point -> point.date().getDayOfWeek() == DayOfWeek.SATURDAY
                        || point.date().getDayOfWeek() == DayOfWeek.SUNDAY)
                .as("completions are shifted off the weekend")
                .allSatisfy(point -> assertThat(point.count()).isZero());
        assertThat(overview.completedIssues()).isPositive();
        assertThat(overview.completionRate()).isBetween(0.01, 0.99);
    }

    @Test
    void issueHistoryIsSpreadAcrossWeeksRatherThanStampedWithTheSeedingTime() {
        List<Issue> issues = issueRepository.findAll();
        Instant now = Instant.now();

        Instant oldest = issues.stream().map(Issue::getCreatedAt).min(Instant::compareTo).orElseThrow();
        Instant newest = issues.stream().map(Issue::getCreatedAt).max(Instant::compareTo).orElseThrow();

        assertThat(Duration.between(oldest, newest).toDays())
                .as("issue creation spread; a single point here means backdating stopped working")
                .isGreaterThanOrEqualTo(49);
        assertThat(newest).isBefore(now);

        List<Issue> completed = issues.stream()
                .filter(issue -> issue.getCompletedAt() != null)
                .toList();
        assertThat(completed).hasSize(31);
        assertThat(completed).allSatisfy(issue -> {
            assertThat(issue.getCompletedAt()).isAfter(issue.getCreatedAt());
            assertThat(issue.getCompletedAt()).isBefore(now);
        });

        assertThat(issueCommentRepository.findAll())
                .allSatisfy(comment -> assertThat(comment.getCreatedAt()).isBefore(now));
    }

    @Test
    void theSavedCopilotDraftIsApplyable() {
        List<AiSuggestion> suggestions = aiSuggestionRepository.findAll();

        assertThat(suggestions).hasSize(1);
        AiSuggestion suggestion = suggestions.get(0);

        assertThat(suggestion.getType()).isEqualTo(AiSuggestionType.ISSUE_BREAKDOWN);
        assertThat(suggestion.getStatus())
                .as("an expired or applied draft would hide the Apply flow")
                .isEqualTo(AiSuggestionStatus.DRAFT);
        assertThat(suggestion.getExpiresAt()).isAfter(Instant.now());
        assertThat(suggestion.getSourceIssue()).isNotNull();
        assertThat(suggestion.getContent().get("items").size())
                .as("Apply needs at least two items to choose between")
                .isGreaterThan(1);
        assertThat(suggestion.getInputTokens())
                .as("no provider call happens during seeding")
                .isNull();
    }

    private Jwt signIn() {
        return jwtService.decode(
                authService.login(new LoginRequest(DEMO_EMAIL, DEMO_PASSWORD))
                        .response()
                        .accessToken()
        );
    }

    private ProjectResponse webPlatform(Jwt jwt) {
        return projectQueryService.listProjects(jwt, false).stream()
                .filter(project -> project.name().equals("Web Platform"))
                .findFirst()
                .orElseThrow();
    }

    private CursorPage<IssueListItemResponse> listIssues(
            Jwt jwt,
            UUID projectId,
            IssuePriority priority,
            UUID assigneeUserId,
            UUID labelId,
            String query
    ) {
        return listIssues(jwt, projectId, priority, assigneeUserId, labelId, query, null);
    }

    private CursorPage<IssueListItemResponse> listIssues(
            Jwt jwt,
            UUID projectId,
            IssuePriority priority,
            UUID assigneeUserId,
            UUID labelId,
            String query,
            UUID workflowStateId
    ) {
        return issueQueryService.listIssues(
                jwt, projectId, null, workflowStateId, priority, assigneeUserId, labelId,
                query, null, ISSUE_PAGE_LIMIT
        );
    }
}
