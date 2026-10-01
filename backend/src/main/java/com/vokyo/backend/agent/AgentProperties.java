package com.vokyo.backend.agent;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Where the planning agent runs and how long a run may take. The agent only reads
 * project data, so a run that times out can be dropped without cleanup.
 */
@Validated
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout
) {
}
