package com.vokyo.backend.ai;

import java.util.Objects;

public record AiGeneration<T>(
        T content,
        String rawOutput,
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens
) {
    public AiGeneration {
        Objects.requireNonNull(content, "content is required");
        Objects.requireNonNull(rawOutput, "rawOutput is required");
    }
}
