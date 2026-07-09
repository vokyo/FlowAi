package com.vokyo.backend.project.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberResponse(
        UUID id,
        UUID userId,
        String email,
        String displayName,
        String role,
        String status,
        Instant joinedAt
) {
}
