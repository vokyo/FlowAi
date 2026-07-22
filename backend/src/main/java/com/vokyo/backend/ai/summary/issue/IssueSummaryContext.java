package com.vokyo.backend.ai.summary.issue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record IssueSummaryContext(
        ProjectContext project,
        IssueContext issue,
        List<CommentContext> comments,
        List<ActivityContext> activities,
        SourceStats sourceStats
) {
    public record ProjectContext(String name, String description) {
    }

    public record IssueContext(
            UUID id,
            String title,
            String description,
            String status,
            String workflowState,
            String priority,
            LocalDate dueDate,
            String assignee,
            List<String> labels,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CommentContext(
            Instant createdAt,
            String author,
            String body
    ) {
    }

    public record ActivityContext(
            Instant createdAt,
            String actor,
            String type,
            Map<String, Object> metadata
    ) {
    }

    public record SourceStats(
            int commentsUsed,
            int activityEventsUsed,
            boolean commentsTruncated,
            boolean activityTruncated,
            boolean contextTruncated
    ) {
    }
}
