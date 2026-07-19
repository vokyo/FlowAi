package com.vokyo.backend.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        boolean enabled,
        @NotNull
        Duration suggestionTtl,

        @NotNull
        Duration requestTimeout,

        @Min(2)
        @Max(8)
        int maxBreakdownItems,

        @Positive
        int includeCommentsLimit,

        @Positive
        int includeActivityLimit,

        @Positive
        int maxContextIssues
) {
}
