package com.vokyo.backend.issue.dto;

import java.time.Instant;
import java.util.UUID;

public record IssueSummaryResponse(
        UUID id,
        UUID projectId,
        String title,
        String description,
        String status,
        String priority,
        Instant createdAt,
        Instant updatedAt,
        long commentCount
) {
}
