package com.vokyo.backend.ai.breakdown;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record IssueBreakdownContext(
        ProjectContext project,
        SourceIssueContext issue,
        String instruction,
        int maxItems,
        List<CommentContext> comments,
        List<ActivityContext> activities,
        AllowedCandidates allowedCandidates,
        SourceStats sourceStats
) {
    public IssueBreakdownContext {
        Objects.requireNonNull(project, "project is required");
        Objects.requireNonNull(issue, "issue is required");
        comments = List.copyOf(comments);
        activities = List.copyOf(activities);
        Objects.requireNonNull(allowedCandidates, "allowedCandidates is required");
        Objects.requireNonNull(sourceStats, "sourceStats is required");
    }

    public record ProjectContext(
            String name,
            String description
    ) {
    }

    public record SourceIssueContext(
            UUID id,
            String title,
            String description,
            String status,
            String priority,
            LocalDate dueDate,
            WorkflowStateContext workflowState,
            AssigneeContext assignee,
            List<UUID> labelIds
    ) {
        public SourceIssueContext {
            Objects.requireNonNull(id, "issue id is required");
            Objects.requireNonNull(title, "issue title is required");
            Objects.requireNonNull(status, "issue status is required");
            Objects.requireNonNull(workflowState, "issue workflowState is required");
            labelIds = List.copyOf(labelIds);
        }
    }

    public record WorkflowStateContext(
            UUID id,
            String name,
            String category
    ) {
    }

    public record AssigneeContext(
            UUID userId,
            String displayName
    ) {
    }

    public record CommentContext(
            Instant createdAt,
            String authorName,
            String body
    ) {
    }

    public record ActivityContext(
            Instant createdAt,
            String actorName,
            String eventType,
            Map<String, Object> metadata
    ) {
        public ActivityContext {
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    public record AllowedCandidates(
            List<LabelCandidate> labels,
            List<MemberCandidate> members,
            List<WorkflowStateCandidate> workflowStates
    ) {
        public AllowedCandidates {
            labels = List.copyOf(labels);
            members = List.copyOf(members);
            workflowStates = List.copyOf(workflowStates);
        }
    }

    public record LabelCandidate(
            UUID id,
            String name,
            String color
    ) {
    }

    public record MemberCandidate(
            UUID userId,
            String displayName,
            String projectRole
    ) {
    }

    public record WorkflowStateCandidate(
            UUID id,
            String name,
            String category
    ) {
    }

    public record SourceStats(
            int commentsUsed,
            int activitiesUsed,
            boolean commentsTruncated,
            boolean activitiesTruncated,
            boolean contextTruncated
    ) {
    }
}
