package com.vokyo.backend.agent.dto;

import com.vokyo.backend.agent.AgentRunState;
import com.vokyo.backend.ai.plan.ProjectPlan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A run and every version of its plan, oldest first. */
public record AgentRunDetailResponse(
    UUID runId,
    UUID projectId,
    String goal,
    LocalDate generatedOn,
    AgentRunState state,
    int latestVersion,
    List<Version> versions,
    Instant createdAt,
    Instant updatedAt
) {

    public record Version(
        int version,
        boolean approvable,
        String rejectionReason,
        String contentHash,
        ProjectPlan plan,
        Instant createdAt
    ) {
    }
}
