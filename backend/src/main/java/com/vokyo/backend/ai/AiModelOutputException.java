package com.vokyo.backend.ai;

import java.util.Objects;

public class AiModelOutputException extends RuntimeException {

    private final String rawOutput;
    private final String provider;
    private final String model;
    private final Integer inputTokens;
    private final Integer outputTokens;

    public AiModelOutputException(
            String errorSummary,
            String rawOutput,
            String provider,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            Throwable cause
    ) {
        super(Objects.requireNonNull(errorSummary, "errorSummary is required"), cause);
        this.rawOutput = rawOutput == null ? "" : rawOutput;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public String rawOutput() {
        return rawOutput;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public Integer inputTokens() {
        return inputTokens;
    }

    public Integer outputTokens() {
        return outputTokens;
    }
}
