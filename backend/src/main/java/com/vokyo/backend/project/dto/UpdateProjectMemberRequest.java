package com.vokyo.backend.project.dto;

import com.vokyo.backend.project.ProjectRole;
import jakarta.validation.constraints.NotNull;

public record UpdateProjectMemberRequest(
        @NotNull
        ProjectRole role
) {
}
