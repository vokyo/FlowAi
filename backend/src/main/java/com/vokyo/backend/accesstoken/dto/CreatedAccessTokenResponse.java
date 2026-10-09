package com.vokyo.backend.accesstoken.dto;

import java.time.Instant;
import java.util.UUID;

/** The only response that carries the token itself; it cannot be read again later. */
public record CreatedAccessTokenResponse(
        UUID id,
        String name,
        String token,
        Instant createdAt,
        Instant expiresAt
) {
}
