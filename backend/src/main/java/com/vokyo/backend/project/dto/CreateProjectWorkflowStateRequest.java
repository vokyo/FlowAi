package com.vokyo.backend.project.dto;

import com.vokyo.backend.project.WorkflowStateCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProjectWorkflowStateRequest(
        @NotBlank
        @Size(max = 60)
        String name,

        @NotNull
        WorkflowStateCategory category
) {
}
