package com.vokyo.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Starts a planning run. The goal limits match what the planning agent accepts, so a
 * goal the agent would refuse is rejected here first.
 */
public record AgentRunRequest(
    @NotNull UUID projectId,
    @NotBlank @Size(max = 500) String goal
) {
}
