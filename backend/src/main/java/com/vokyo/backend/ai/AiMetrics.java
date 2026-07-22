package com.vokyo.backend.ai;

import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AiMetrics {
    private static final Logger log = LoggerFactory.getLogger(AiMetrics.class);
    private static final String UNKNOWN = "unknown";
    private final MeterRegistry meterRegistry;

    public AiMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    public void complete(
            Timer.Sample sample,
            String feature,
            String result,
            String provider,
            String model
    ) {
        meterRegistry.counter(
                "flowai.ai.requests",
                "feature", feature,
                "result", result
        ).increment();
        long durationNanos = sample.stop(Timer.builder("flowai.ai.duration")
                .tags(
                        "feature", feature,
                        "provider", tag(provider),
                        "model", tag(model)
                )
                .register(meterRegistry));
        log.info(
                "event=ai_request feature={} result={} provider={} model={} durationMs={}",
                feature, result, tag(provider), tag(model), durationNanos / 1_000_000L
        );
    }

    public void recordTokens(
            String feature,
            String model,
            Integer inputTokens,
            Integer outputTokens
    ) {
        incrementTokens(feature, "input", model, inputTokens);
        incrementTokens(feature, "output", model, outputTokens);
    }

    public void recordSuggestion(AiSuggestionType type, AiSuggestionStatus status) {
        meterRegistry.counter(
                "flowai.ai.suggestions",
                "type", type.name().toLowerCase(),
                "status", status.name().toLowerCase()
        ).increment();
    }

    public void recordGenerationMetadata(
            String feature,
            String promptVersion,
            String provider,
            String model,
            Integer inputTokens,
            Integer outputTokens
    ) {
        log.info(
                "event=ai_generation feature={} promptVersion={} provider={} model={} inputTokens={} outputTokens={}",
                feature,
                tag(promptVersion),
                tag(provider),
                tag(model),
                inputTokens == null ? 0 : inputTokens,
                outputTokens == null ? 0 : outputTokens
        );
    }

    public void recordApply(String result, int itemCount) {
        meterRegistry.counter(
                "flowai.ai.apply",
                "result", result
        ).increment();
        if (itemCount > 0) {
            meterRegistry.counter(
                    "flowai.ai.apply.items",
                    "result", result
            ).increment(itemCount);
        }
    }

    public void recordRateLimitRejection() {
        meterRegistry.counter(
                "flowai.ai.rate_limit.rejected",
                "scope", "user_workspace"
        ).increment();
    }

    private void incrementTokens(
            String feature,
            String direction,
            String model,
            Integer tokens
    ) {
        if (tokens == null || tokens <= 0) return;
        meterRegistry.counter(
                "flowai.ai.tokens",
                "direction", direction,
                "feature", feature,
                "model", tag(model)
        ).increment(tokens);
    }

    private String tag(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
