package com.vokyo.backend.ai.breakdown;

import com.vokyo.backend.issue.IssuePriority;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IssueBreakdownResult(
        String overview,
        List<Item> items,
        List<String> warnings
) {

    public record Item(
            String clientItemId,
            String title,
            String description,
            IssuePriority priority,
            List<String> acceptanceCriteria,
            List<UUID> suggestedLabelIds,
            UUID suggestedAssigneeUserId,
            LocalDate dueDate,
            List<String> dependsOnClientItemIds
    ) {
    }
}
