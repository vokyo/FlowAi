package com.vokyo.backend.workspace.dto;

import jakarta.validation.constraints.NotBlank;

public record SwitchWorkspaceRequest(
        @NotBlank
        String refreshToken
) {
}
