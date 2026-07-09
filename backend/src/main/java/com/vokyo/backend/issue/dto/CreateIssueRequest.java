package com.vokyo.backend.issue.dto;

import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateIssueRequest(
        @NotNull
        UUID projectId,

        @NotBlank
        @Size(max = 240)
        String title,

        @Size(max = 10000)
        String description,

        List<UUID> labelIds,

        UUID assigneeUserId,

        UUID workflowStateId,

        IssueStatus status,

        IssuePriority priority,

        LocalDate dueDate
) {
}
