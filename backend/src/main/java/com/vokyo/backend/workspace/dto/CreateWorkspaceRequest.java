package com.vokyo.backend.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank
        @Size(max = 160)
        String name
) {
}
