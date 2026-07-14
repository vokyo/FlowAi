package com.vokyo.backend.security.ratelimit;

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision permit() {
        return new RateLimitDecision(true, 0);
    }

    public static RateLimitDecision rejected(long nanosToWait) {
        long seconds = Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
        return new RateLimitDecision(false, seconds);
    }
}
