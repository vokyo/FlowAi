package com.vokyo.backend.issue.dto;

import com.vokyo.backend.auth.dto.UserResponse;

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
        String priority,
        UserResponse creator,
        UserResponse assignee,
        LocalDate dueDate,
        Instant createdAt,
        Instant updatedAt,
        List<IssueCommentResponse> comments
) {
}
