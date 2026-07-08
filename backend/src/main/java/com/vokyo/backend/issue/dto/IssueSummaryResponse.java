package com.vokyo.backend.issue.dto;

import com.vokyo.backend.auth.dto.UserResponse;

import java.time.Instant;
import java.util.UUID;

public record IssueSummaryResponse(
        UUID id,
        UUID projectId,
        String title,
        String description,
        String status,
        String priority,
        UserResponse creator,
        Instant createdAt,
        Instant updatedAt,
        long commentCount
) {
}
