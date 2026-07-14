package com.vokyo.backend.project.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DeleteProjectWorkflowStateRequest(
        @NotNull UUID replacementWorkflowStateId
) {
}
