package com.vokyo.backend.ai.summary.project;

import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.ai.summary.project.dto.ProjectSummaryRequest;
import com.vokyo.backend.analytics.AnalyticsService;
import com.vokyo.backend.analytics.dto.AnalyticsOverviewResponse;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class ProjectSummaryContextBuilder {

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;
    private final AnalyticsService analyticsService;
    private final IssueRepository issueRepository;
    private final AiProperties aiProperties;

    public ProjectSummaryContextBuilder(
            WorkspaceAccessService workspaceAccessService,
            ProjectAccessService projectAccessService,
            AnalyticsService analyticsService,
            IssueRepository issueRepository,
            AiProperties aiProperties
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
        this.analyticsService = analyticsService;
        this.issueRepository = issueRepository;
        this.aiProperties = aiProperties;
    }

    @Transactional(readOnly = true)
    public BuiltProjectSummaryContext build(
            Jwt jwt,
            UUID projectId,
            ProjectSummaryRequest request
    ) {
        Objects.requireNonNull(request, "request is required");
        CurrentWorkspaceContext context =
                workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(
                projectId,
                context
        );
        int rangeDays = request.effectiveRangeDays();
        AnalyticsOverviewResponse overview = analyticsService.getOverview(
                jwt,
                projectId,
                rangeDays
        );
        List<Issue> activeIssues = issueRepository.findAllActiveForAiSummary(
                context.workspace().getId(),
                projectId
        );
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Issue> sorted = activeIssues.stream()
                .sorted(issueComparator(today))
                .toList();
        int limit = aiProperties.maxContextIssues();
        List<Issue> retained = sorted.subList(0, Math.min(limit, sorted.size()));
        boolean truncated = sorted.size() > limit;

        ProjectSummaryContext.SourceStats sourceStats =
                new ProjectSummaryContext.SourceStats(
                        retained.size(),
                        activeIssues.size(),
                        rangeDays,
                        truncated
                );
        ProjectSummaryContext modelContext = new ProjectSummaryContext(
                new ProjectSummaryContext.ProjectContext(
                        project.getId(),
                        project.getName(),
                        project.getDescription(),
                        project.getArchivedAt() != null
                ),
                rangeDays,
                request.normalizedFocus(),
                analytics(activeIssues, overview, today),
                retained.stream().map(this::toIssueContext).toList(),
                sourceStats
        );
        return new BuiltProjectSummaryContext(context, project, modelContext);
    }

    static Comparator<Issue> issueComparator(LocalDate today) {
        return Comparator
                .comparingInt((Issue issue) -> isOverdue(issue, today) ? 0 : 1)
                .thenComparingInt(ProjectSummaryContextBuilder::priorityRank)
                .thenComparing(Issue::getUpdatedAt, Comparator.reverseOrder())
                .thenComparing(Issue::getId);
    }

    private ProjectSummaryContext.AnalyticsContext analytics(
            List<Issue> activeIssues,
            AnalyticsOverviewResponse overview,
            LocalDate today
    ) {
        return new ProjectSummaryContext.AnalyticsContext(
                overview.totalIssues(),
                overview.completedIssues(),
                overview.completionRate(),
                overview.archivedIssues(),
                activeIssues.stream().filter(issue -> isOverdue(issue, today)).count(),
                activeIssues.stream().filter(ProjectSummaryContextBuilder::isHighPriority).count(),
                activeIssues.stream().filter(issue -> issue.getAssigneeUser() == null).count(),
                overview.statusDistribution().stream()
                        .map(value -> new ProjectSummaryContext.StatusCount(
                                value.category(),
                                value.count()
                        ))
                        .toList(),
                overview.assigneeDistribution().stream()
                        .map(value -> new ProjectSummaryContext.AssigneeCount(
                                value.displayName(),
                                value.count()
                        ))
                        .toList(),
                overview.completionTrend().stream()
                        .map(value -> new ProjectSummaryContext.CompletionPoint(
                                value.date(),
                                value.count()
                        ))
                        .toList()
        );
    }

    private ProjectSummaryContext.IssueContext toIssueContext(Issue issue) {
        User assignee = issue.getAssigneeUser();
        return new ProjectSummaryContext.IssueContext(
                issue.getId(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getWorkflowState().getCategory().name(),
                issue.getWorkflowState().getName(),
                issue.getPriority() == null ? null : issue.getPriority().name(),
                issue.getDueDate(),
                assignee == null ? null : assignee.getDisplayName(),
                issue.getLabels().stream().map(ProjectLabel::getName).toList(),
                issue.getUpdatedAt()
        );
    }

    private static boolean isOverdue(Issue issue, LocalDate today) {
        return issue.getDueDate() != null
                && issue.getDueDate().isBefore(today)
                && issue.getWorkflowState().getCategory() != WorkflowStateCategory.DONE;
    }

    private static boolean isHighPriority(Issue issue) {
        return issue.getPriority() == IssuePriority.URGENT
                || issue.getPriority() == IssuePriority.HIGH;
    }

    private static int priorityRank(Issue issue) {
        if (issue.getPriority() == IssuePriority.URGENT) return 0;
        if (issue.getPriority() == IssuePriority.HIGH) return 1;
        return 2;
    }
}
