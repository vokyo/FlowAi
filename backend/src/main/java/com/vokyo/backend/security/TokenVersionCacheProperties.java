package com.vokyo.backend.security;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.security.token-version-cache")
public record TokenVersionCacheProperties(
        // How long a cached version lives in Redis. Redis refuses an expiry of zero,
        // so a zero here would fail every write rather than disable the cache.
        @NotNull
        @DurationMin(millis = 1)
        Duration ttl,
        // How long an instance leaves Redis alone after a Redis command fails.
        @NotNull
        @DurationMin(millis = 1)
        Duration skipAfterFailure
) {
}
