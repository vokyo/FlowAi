package com.vokyo.backend.project.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record ReorderProjectWorkflowStatesRequest(
        @NotEmpty
        List<UUID> workflowStateIds
) {
}
