package com.vokyo.backend.accesstoken.dto;

import java.time.Instant;
import java.util.UUID;

public record AccessTokenResponse(
        UUID id,
        String name,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        boolean expired
) {
}
