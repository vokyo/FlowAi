package com.vokyo.backend.activity.dto;

import com.vokyo.backend.auth.dto.UserResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ActivityEventResponse(
        UUID id,
        String eventType,
        UserResponse actor,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
