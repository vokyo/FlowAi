package com.vokyo.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProjectLabelRequest(
        @NotBlank @Size(max = 60) String name,
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color
) {
}
