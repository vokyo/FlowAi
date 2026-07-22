package com.vokyo.backend.issue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IssueCreationCommand(
        String title,
        String description,
        List<UUID> labelIds,
        UUID assigneeUserId,
        UUID workflowStateId,
        IssueStatus status,
        IssuePriority priority,
        LocalDate dueDate
) {
}
