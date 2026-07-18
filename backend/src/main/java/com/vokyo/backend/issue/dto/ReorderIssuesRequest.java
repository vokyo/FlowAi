package com.vokyo.backend.issue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReorderIssuesRequest(
        @NotNull
        UUID issueId,

        @NotNull
        UUID workflowStateId,

        UUID previousIssueId,

        UUID nextIssueId
) {
}
