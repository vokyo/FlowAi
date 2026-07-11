package com.vokyo.backend.workspace.dto;

import com.vokyo.backend.workspace.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceInvitationRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotNull
        WorkspaceRole role
) {
}
