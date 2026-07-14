package com.vokyo.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 5000) String description
) {
}
