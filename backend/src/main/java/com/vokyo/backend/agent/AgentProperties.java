package com.vokyo.backend.agent;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Whether the planning agent runs at all, where it runs and how long a run may take.
 * The agent only reads project data, so a run that times out can be dropped without
 * cleanup. With the agent off, runs that already exist can still be read, approved
 * and cancelled; only starting and revising need it.
 */
@Validated
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(
    boolean enabled,
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout
) {
}
