package com.vokyo.backend.issue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MoveIssueStateRequest(
        @NotNull
        UUID workflowStateId
) {
}
