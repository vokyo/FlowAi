package com.vokyo.backend.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
        int maxContextIssues,

        @NotNull
        AiRateLimit rateLimit
) {
    @ConstructorBinding
    public AiProperties {
    }

    public AiProperties(
            boolean enabled,
            Duration suggestionTtl,
            Duration requestTimeout,
            int maxBreakdownItems,
            int includeCommentsLimit,
            int includeActivityLimit,
            int maxContextIssues
    ) {
        this(
                enabled,
                suggestionTtl,
                requestTimeout,
                maxBreakdownItems,
                includeCommentsLimit,
                includeActivityLimit,
                maxContextIssues,
                new AiRateLimit(true, 10, Duration.ofMinutes(1))
        );
    }

    public record AiRateLimit(
            boolean enabled,
            @Positive long capacity,
            @NotNull Duration window
    ) {
    }
}
