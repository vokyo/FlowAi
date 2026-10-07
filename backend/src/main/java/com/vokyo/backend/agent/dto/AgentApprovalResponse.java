package com.vokyo.backend.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The issues an approved version created; approving it again returns the same. */
public record AgentApprovalResponse(
    UUID runId,
    int version,
    List<UUID> createdIssueIds,
    Instant approvedAt
) {
}
