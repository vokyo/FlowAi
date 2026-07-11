package com.vokyo.backend.analytics.dto;

import java.util.List;
import java.util.UUID;

public record AnalyticsOverviewResponse(
        UUID projectId,
        int rangeDays,
        long totalIssues,
        long completedIssues,
        double completionRate,
        long archivedIssues,
        List<AnalyticsStatusDistributionResponse> statusDistribution,
        List<AnalyticsAssigneeDistributionResponse> assigneeDistribution,
        List<AnalyticsCompletionTrendPointResponse> completionTrend
) {
}
