package com.vokyo.backend.agent;

import com.vokyo.backend.ai.plan.ProjectPlan;

import java.util.List;

/**
 * The agent's answer to POST /runs, exactly as it arrives. Nothing in it is trusted:
 * the plan is validated before it is saved, and the other fields are checked against
 * the status they come with.
 */
public record AgentRunResult(
    AgentRunStatus status,
    ProjectPlan plan,
    List<String> missing,
    String reason,
    Stats stats
) {

    public record Stats(Integer decisionRounds, Integer toolCalls) {
    }
}
