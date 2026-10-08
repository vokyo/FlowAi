package com.vokyo.backend.security.ratelimit;

/**
 * Token buckets kept outside this instance, so every backend instance draws from the
 * same ones. Any call can fail with a DataAccessException when the store does not
 * answer in time; the rate limiter then counts on this instance instead.
 */
public interface SharedBuckets {

    /** Takes a token, starting from a full bucket if there is none yet. */
    RateLimitDecision consume(BucketKey key);

    /** Gives a token back, never beyond the bucket's capacity. */
    void refund(BucketKey key);

    void clear(BucketKey key);

    /** Removes every bucket, for tests that need a clean slate. */
    void clearAll();
}
