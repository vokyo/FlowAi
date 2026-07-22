package com.vokyo.backend.ai;

public class AiRateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public AiRateLimitExceededException(long retryAfterSeconds) {
        super("AI generation rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
