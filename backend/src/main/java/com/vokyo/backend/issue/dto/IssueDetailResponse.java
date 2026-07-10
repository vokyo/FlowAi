package com.vokyo.backend.issue.dto;

import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.project.dto.ProjectLabelResponse;
import com.vokyo.backend.project.dto.ProjectWorkflowStateResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IssueDetailResponse(
        UUID id,
        UUID projectId,
        String title,
        String description,
        String status,
        ProjectWorkflowStateResponse workflowState,
        String priority,
        List<ProjectLabelResponse> labels,
        UserResponse creator,
        UserResponse assignee,
        LocalDate dueDate,
        Instant archivedAt,
        long boardPosition,
        Instant createdAt,
        Instant updatedAt,
        List<IssueCommentResponse> comments
) {
}
