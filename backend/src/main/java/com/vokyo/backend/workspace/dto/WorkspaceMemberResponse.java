package com.vokyo.backend.workspace.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberResponse(
        UUID id,
        UUID userId,
        String email,
        String displayName,
        String role,
        String status,
        Instant joinedAt
) {
}
