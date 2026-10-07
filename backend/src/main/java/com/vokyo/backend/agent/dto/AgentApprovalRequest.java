package com.vokyo.backend.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Approves one version of a run's plan. The content hash is the one the user was
 * shown, which ties the approval to the plan they read.
 */
public record AgentApprovalRequest(
    @NotNull @Min(1) @Max(5) Integer version,
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contentHash
) {
}
