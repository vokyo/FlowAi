package com.vokyo.backend.ai.summary.project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProjectSummaryContext(
        ProjectContext project,
        int rangeDays,
        String focus,
        AnalyticsContext analytics,
        List<IssueContext> issues,
        SourceStats sourceStats
) {
    public record ProjectContext(
            UUID id,
            String name,
            String description,
            boolean archived
    ) {
    }

    public record AnalyticsContext(
            long totalIssues,
            long completedIssues,
            double completionRate,
            long archivedIssues,
            long overdueIssues,
            long highPriorityIssues,
            long unassignedIssues,
            List<StatusCount> workflowDistribution,
            List<AssigneeCount> assigneeDistribution,
            List<CompletionPoint> completionTrend
    ) {
    }

    public record StatusCount(String category, long count) {
    }

    public record AssigneeCount(String displayName, long count) {
    }

    public record CompletionPoint(LocalDate date, long count) {
    }

    public record IssueContext(
            UUID id,
            String title,
            String description,
            String workflowCategory,
            String workflowState,
            String priority,
            LocalDate dueDate,
            String assignee,
            List<String> labels,
            Instant updatedAt
    ) {
    }

    public record SourceStats(
            int activeIssuesUsed,
            long totalActiveIssues,
            int rangeDays,
            boolean contextTruncated
    ) {
    }
}
