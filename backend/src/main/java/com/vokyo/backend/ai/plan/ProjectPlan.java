package com.vokyo.backend.ai.plan;

import com.vokyo.backend.issue.IssuePriority;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The plan the planning agent returns and a PROJECT_PLAN suggestion stores. The
 * Python service produces the same shape, so the field names are the contract.
 */
public record ProjectPlan(
        String overview,
        List<Item> items
) {

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
