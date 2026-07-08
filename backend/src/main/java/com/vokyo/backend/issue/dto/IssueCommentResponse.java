package com.vokyo.backend.issue.dto;

import com.vokyo.backend.auth.dto.UserResponse;

import java.time.Instant;
import java.util.UUID;

public record IssueCommentResponse(
        UUID id,
        UUID issueId,
        UserResponse author,
        String body,
        Instant createdAt
) {
}
