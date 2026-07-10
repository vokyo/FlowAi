package com.vokyo.backend.issue.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReorderIssuesRequest(
        @NotNull
        UUID issueId,

        @NotNull
        UUID workflowStateId,

        @NotEmpty
        List<@NotNull UUID> orderedIssueIds
) {
}
