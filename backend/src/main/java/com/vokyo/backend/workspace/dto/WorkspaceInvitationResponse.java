package com.vokyo.backend.workspace.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceInvitationResponse(
        UUID id,
        String email,
        String role,
        String status,
        Instant expiresAt,
        Instant acceptedAt,
        Instant createdAt
) {
}
