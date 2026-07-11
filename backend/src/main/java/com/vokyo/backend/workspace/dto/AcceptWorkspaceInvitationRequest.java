package com.vokyo.backend.workspace.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptWorkspaceInvitationRequest(
        @NotBlank
        String refreshToken
) {
}
