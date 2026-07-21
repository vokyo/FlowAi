package com.vokyo.backend.ai.breakdown;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class IssueBreakdownMetrics {

    private static final String FEATURE = "issue_breakdown";
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    public IssueBreakdownMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    public void complete(
            Timer.Sample sample,
            String result,
            String provider,
            String model
    ) {
        String safeProvider = tag(provider);
        String safeModel = tag(model);
        meterRegistry.counter(
                "flowai.ai.requests",
                "feature", FEATURE,
                "result", result
        ).increment();
        sample.stop(Timer.builder("flowai.ai.duration")
                .tags(
                        "feature", FEATURE,
                        "provider", safeProvider,
                        "model", safeModel
                )
                .register(meterRegistry));
    }

    public void recordTokens(String model, Integer inputTokens, Integer outputTokens) {
        String safeModel = tag(model);
        incrementTokens("input", safeModel, inputTokens);
        incrementTokens("output", safeModel, outputTokens);
    }

    public void recordDraft() {
        meterRegistry.counter(
                "flowai.ai.suggestions",
                "type", "issue_breakdown",
                "status", "draft"
        ).increment();
    }

    private void incrementTokens(String direction, String model, Integer tokens) {
        if (tokens == null || tokens <= 0) {
            return;
        }
        meterRegistry.counter(
                "flowai.ai.tokens",
                "direction", direction,
                "feature", FEATURE,
                "model", model
        ).increment(tokens);
    }

    private String tag(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
