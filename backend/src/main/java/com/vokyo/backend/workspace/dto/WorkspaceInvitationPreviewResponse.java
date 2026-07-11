package com.vokyo.backend.workspace.dto;

import java.time.Instant;

public record WorkspaceInvitationPreviewResponse(
        String workspaceName,
        String email,
        String role,
        String status,
        Instant expiresAt
) {
}
