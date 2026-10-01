package com.vokyo.backend.agent.dto;

import com.vokyo.backend.agent.AgentRunResult;
import com.vokyo.backend.agent.AgentRunStatus;
import com.vokyo.backend.ai.plan.ProjectPlan;

import java.util.List;
import java.util.UUID;

/**
 * How a finished run ended. PLANNED comes with the saved suggestion and its validated
 * plan; INSUFFICIENT_INFO says what was missing and saves nothing. A run that failed
 * is reported as an error response instead.
 */
public record AgentRunResponse(
    UUID runId,
    AgentRunStatus status,
    UUID suggestionId,
    ProjectPlan plan,
    List<String> missing,
    AgentRunResult.Stats stats
) {
}
