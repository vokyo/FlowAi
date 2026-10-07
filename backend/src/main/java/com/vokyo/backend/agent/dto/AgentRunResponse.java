package com.vokyo.backend.agent.dto;

import com.vokyo.backend.agent.AgentRunResult;
import com.vokyo.backend.agent.AgentRunStatus;
import com.vokyo.backend.ai.plan.ProjectPlan;

import java.util.List;
import java.util.UUID;

/**
 * How starting or revising a run ended. PLANNED comes with the version it saved: an
 * approvable one with its content hash, which approving it requires, or one that
 * cannot be approved and says why, so the user can revise it. INSUFFICIENT_INFO says
 * what was missing and saves no version. A run that failed is an error response.
 */
public record AgentRunResponse(
    UUID runId,
    AgentRunStatus status,
    Integer version,
    Boolean approvable,
    String rejectionReason,
    String contentHash,
    ProjectPlan plan,
    List<String> missing,
    AgentRunResult.Stats stats
) {
}
