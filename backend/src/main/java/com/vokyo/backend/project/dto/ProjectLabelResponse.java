package com.vokyo.backend.project.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectLabelResponse(
        UUID id,
        UUID projectId,
        String name,
        String color,
        Instant createdAt,
        Instant updatedAt
) {
}
