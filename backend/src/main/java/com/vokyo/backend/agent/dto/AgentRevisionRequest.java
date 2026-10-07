package com.vokyo.backend.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Asks the agent to revise the plan. basedOnVersion is the version the user read; if
 * another one has replaced it since, the revision is refused instead of being applied
 * to a plan they have not seen.
 */
public record AgentRevisionRequest(
    @NotNull @Min(1) @Max(5) Integer basedOnVersion,
    @NotBlank @Size(max = 1000) String feedback
) {
}
