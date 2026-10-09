package com.vokyo.backend.mcp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(@NotNull @Valid RateLimit rateLimit) {

    public record RateLimit(@Min(1) long capacity, @NotNull Duration window) {
    }
}
