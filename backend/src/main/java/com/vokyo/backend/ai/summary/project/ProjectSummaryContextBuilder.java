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
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
                context,
                project,
                rangeDays
        );
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        UUID workspaceId = context.workspace().getId();
        int limit = aiProperties.maxContextIssues();

        // Ranking and counting both run in the database. Pulling every active issue
        // in to sort it here made the cost of a summary scale with project size.
        IssueRepository.AiSummaryIssueStats stats = issueRepository.summarizeActiveForAiSummary(
                workspaceId,
                projectId,
                today,
                WorkflowStateCategory.DONE,
                IssuePriority.URGENT,
                IssuePriority.HIGH
        );
        List<Issue> retained = issueRepository.findRankedActiveForAiSummary(
                workspaceId,
                projectId,
                today,
                WorkflowStateCategory.DONE,
                IssuePriority.URGENT,
                IssuePriority.HIGH,
                PageRequest.of(0, limit)
        );
        boolean truncated = stats.getTotalActive() > limit;

        ProjectSummaryContext.SourceStats sourceStats =
                new ProjectSummaryContext.SourceStats(
                        retained.size(),
                        stats.getTotalActive(),
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
                analytics(stats, overview),
                retained.stream().map(this::toIssueContext).toList(),
                sourceStats
        );
        return new BuiltProjectSummaryContext(context, project, modelContext);
    }

    private ProjectSummaryContext.AnalyticsContext analytics(
            IssueRepository.AiSummaryIssueStats stats,
            AnalyticsOverviewResponse overview
    ) {
        return new ProjectSummaryContext.AnalyticsContext(
                overview.totalIssues(),
                overview.completedIssues(),
                overview.completionRate(),
                overview.archivedIssues(),
                stats.getOverdue(),
                stats.getHighPriority(),
                stats.getUnassigned(),
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

}
