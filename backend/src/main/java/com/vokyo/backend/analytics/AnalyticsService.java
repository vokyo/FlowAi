package com.vokyo.backend.analytics;

import com.vokyo.backend.analytics.dto.AnalyticsAssigneeDistributionResponse;
import com.vokyo.backend.analytics.dto.AnalyticsCompletionTrendPointResponse;
import com.vokyo.backend.analytics.dto.AnalyticsOverviewResponse;
import com.vokyo.backend.analytics.dto.AnalyticsStatusDistributionResponse;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalyticsService {

    private static final Set<Integer> ALLOWED_RANGE_DAYS = Set.of(7, 30, 90);

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;
    private final AnalyticsRepository analyticsRepository;

    public AnalyticsService(
            WorkspaceAccessService workspaceAccessService,
            ProjectAccessService projectAccessService,
            AnalyticsRepository analyticsRepository
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
        this.analyticsRepository = analyticsRepository;
    }

    @Transactional(readOnly = true)
    public AnalyticsOverviewResponse getOverview(Jwt jwt, UUID projectId, int days) {
        if (!ALLOWED_RANGE_DAYS.contains(days)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Analytics range must be 7, 30, or 90 days"
            );
        }

        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        UUID workspaceId = context.workspace().getId();
        AnalyticsRepository.SummaryProjection summary = analyticsRepository.summarize(
                workspaceId,
                project.getId()
        );

        long totalIssues = summary.getTotalIssues();
        long completedIssues = summary.getCompletedIssues();
        double completionRate = totalIssues == 0
                ? 0.0
                : (double) completedIssues / totalIssues;

        return new AnalyticsOverviewResponse(
                project.getId(),
                days,
                totalIssues,
                completedIssues,
                completionRate,
                summary.getArchivedIssues(),
                statusDistribution(workspaceId, project.getId()),
                assigneeDistribution(workspaceId, project.getId()),
                completionTrend(workspaceId, project.getId(), days)
        );
    }

    private List<AnalyticsStatusDistributionResponse> statusDistribution(
            UUID workspaceId,
            UUID projectId
    ) {
        Map<WorkflowStateCategory, Long> counts = new EnumMap<>(WorkflowStateCategory.class);
        analyticsRepository.countByStatusCategory(workspaceId, projectId)
                .forEach(projection -> counts.put(
                        WorkflowStateCategory.valueOf(projection.getCategory()),
                        projection.getIssueCount()
                ));

        return List.of(WorkflowStateCategory.TODO, WorkflowStateCategory.IN_PROGRESS, WorkflowStateCategory.DONE)
                .stream()
                .map(category -> new AnalyticsStatusDistributionResponse(
                        category.name(),
                        counts.getOrDefault(category, 0L)
                ))
                .toList();
    }

    private List<AnalyticsAssigneeDistributionResponse> assigneeDistribution(
            UUID workspaceId,
            UUID projectId
    ) {
        return analyticsRepository.countByAssignee(workspaceId, projectId)
                .stream()
                .map(projection -> new AnalyticsAssigneeDistributionResponse(
                        projection.getUserId(),
                        projection.getDisplayName(),
                        projection.getEmail(),
                        projection.getIssueCount()
                ))
                .toList();
    }

    private List<AnalyticsCompletionTrendPointResponse> completionTrend(
            UUID workspaceId,
            UUID projectId,
            int days
    ) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate startDate = today.minusDays(days - 1L);
        Instant startInclusive = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endExclusive = today.plusDays(1L).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<LocalDate, Long> counts = analyticsRepository.countCompletionsByUtcDate(
                        workspaceId,
                        projectId,
                        startInclusive,
                        endExclusive
                )
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        AnalyticsRepository.CompletionTrendProjection::getCompletionDate,
                        AnalyticsRepository.CompletionTrendProjection::getIssueCount
                ));

        return startDate.datesUntil(today.plusDays(1L))
                .map(date -> new AnalyticsCompletionTrendPointResponse(
                        date,
                        counts.getOrDefault(date, 0L)
                ))
                .toList();
    }
}
