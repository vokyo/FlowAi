package com.vokyo.backend.ai;

import com.vokyo.backend.security.ratelimit.RateLimitDecision;
import com.vokyo.backend.security.ratelimit.RateLimitService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import org.springframework.stereotype.Component;

@Component
public class AiGenerationRateLimiter {
    private static final String POLICY_NAME = "ai_generation";

    private final AiProperties properties;
    private final RateLimitService rateLimitService;
    private final AiMetrics metrics;

    public AiGenerationRateLimiter(
            AiProperties properties,
            RateLimitService rateLimitService,
            AiMetrics metrics
    ) {
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.metrics = metrics;
    }

    public void requirePermit(CurrentWorkspaceContext context) {
        AiProperties.AiRateLimit settings = properties.rateLimit();
        if (!settings.enabled()) return;

        String identity = context.user().getId() + ":" + context.workspace().getId();
        RateLimitDecision decision = rateLimitService.consume(
                POLICY_NAME,
                settings.capacity(),
                settings.window(),
                identity
        );
        if (!decision.allowed()) {
            metrics.recordRateLimitRejection();
            throw new AiRateLimitExceededException(decision.retryAfterSeconds());
        }
    }
}
