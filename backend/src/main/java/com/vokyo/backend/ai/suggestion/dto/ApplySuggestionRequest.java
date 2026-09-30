package com.vokyo.backend.ai.suggestion.dto;

import com.vokyo.backend.issue.IssuePriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Which fields matter depends on the suggestion being applied: an issue breakdown
 * sends every saved item with the user's edits, while a project plan is applied
 * exactly as saved and sends only the idempotency key.
 */
public record ApplySuggestionRequest(
        @NotNull UUID idempotencyKey,
        @Size(max = 8) List<@Valid Item> items
) {

    public record Item(
            @NotBlank @Size(max = 100) String clientItemId,
            @NotNull Boolean selected,
            @Size(max = 240) String title,
            @Size(max = 10_000) String description,
            IssuePriority priority,
            List<UUID> labelIds,
            UUID assigneeUserId,
            UUID workflowStateId,
            LocalDate dueDate
    ) {
    }
}
