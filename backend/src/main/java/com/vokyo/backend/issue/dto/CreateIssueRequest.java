package com.vokyo.backend.issue.dto;

import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateIssueRequest(
        @NotNull
        UUID projectId,

        @NotBlank
        @Size(max = 240)
        String title,

        @Size(max = 10000)
        String description,

        IssueStatus status,

        IssuePriority priority
) {
}
