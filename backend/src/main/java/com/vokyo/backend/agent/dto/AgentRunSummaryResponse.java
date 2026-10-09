package com.vokyo.backend.agent.dto;

import com.vokyo.backend.agent.AgentRunState;

import java.time.Instant;
import java.util.UUID;

/** One of the caller's runs on a project, for finding it again without its plan. */
public record AgentRunSummaryResponse(
    UUID runId,
    UUID projectId,
    String goal,
    AgentRunState state,
    int latestVersion,
    Instant createdAt,
    Instant updatedAt
) {
}
