package com.vokyo.backend.project.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectWorkflowStateResponse(
        UUID id,
        UUID projectId,
        String name,
        String category,
        int position,
        Instant createdAt,
        Instant updatedAt
) {
}
