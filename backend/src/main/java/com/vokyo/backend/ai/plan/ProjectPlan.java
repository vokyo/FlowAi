package com.vokyo.backend.ai.plan;

import com.vokyo.backend.issue.IssuePriority;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The plan the planning agent returns and a PROJECT_PLAN suggestion stores. The
 * Python service produces the same shape, so the field names are the contract.
 * existingIssues names issues that already cover part of the goal; only items
 * become new issues when the plan is approved.
 */
public record ProjectPlan(
        String overview,
        List<ExistingIssue> existingIssues,
        List<Item> items
) {

    /** A plan that reuses no existing issue. */
    public ProjectPlan(String overview, List<Item> items) {
        this(overview, List.of(), items);
    }

    /**
     * The ids the plan names as existing issues, for looking up which of them are
     * still active in the project. Tolerates the missing or partial lists of plans
     * that have not been validated yet.
     */
    public Set<UUID> referencedIssueIds() {
        if (existingIssues == null) {
            return Set.of();
        }
        return existingIssues.stream()
                .filter(Objects::nonNull)
                .map(ExistingIssue::issueId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    public record ExistingIssue(
            UUID issueId,
            String reason
    ) {
    }

    public record Item(
            String clientItemId,
            String title,
            String description,
            IssuePriority priority,
            UUID suggestedAssigneeUserId,
            LocalDate dueDate
    ) {
    }
}
