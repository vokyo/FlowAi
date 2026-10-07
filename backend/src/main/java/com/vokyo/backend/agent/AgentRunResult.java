package com.vokyo.backend.agent;

import com.vokyo.backend.ai.plan.ProjectPlan;

import java.util.List;

/**
 * The agent's answer to POST /runs and to a revision, exactly as it arrives. Nothing
 * in it is trusted: the plan is validated before it is saved, and the other fields
 * are checked against the status they come with. checkpointId says where the agent
 * stopped for review with this plan, so a later revision continues from there.
 */
public record AgentRunResult(
    AgentRunStatus status,
    ProjectPlan plan,
    List<String> missing,
    String reason,
    Stats stats,
    String checkpointId
) {

    public record Stats(Integer decisionRounds, Integer toolCalls) {
    }
}
