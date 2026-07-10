package com.vokyo.backend.issue.dto;

import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;

import java.util.List;

public record BoardColumnResponse(
        ProjectWorkflowStateResponse workflowState,
        List<IssueSummaryResponse> issues
) {
}
