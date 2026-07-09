package com.vokyo.backend.project.dto;

import com.vokyo.backend.project.ProjectRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddProjectMemberRequest(
        @NotNull
        UUID userId,

        @NotNull
        ProjectRole role
) {
}
