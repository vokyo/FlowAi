package com.vokyo.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank
        String secret,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl,

        // How long a just-rotated refresh token keeps being treated as a benign race
        // rather than a replay. Several tabs restoring together all send the same
        // cookie before any of them sees the new one, and that must not look like a
        // stolen token. See RefreshTokenReuseHandler.
        @NotNull
        Duration refreshReuseGrace
) {
}
