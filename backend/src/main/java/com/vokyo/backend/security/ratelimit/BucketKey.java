package com.vokyo.backend.security.ratelimit;

import java.time.Duration;

/**
 * The bucket a request draws from: one per policy, its limit and the client it
 * counts. A changed limit starts a new bucket instead of reusing one sized for the
 * old limit.
 */
public record BucketKey(String policyName, long capacity, Duration window, String identity) {
}
