package com.vokyo.backend.security.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Keeps each bucket in Redis and changes it only through redis/token-bucket.lua,
 * which Redis runs as one step. A key names the policy and its limit, then a hash of
 * the client, so no IP address or email sits in Redis.
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
class RedisBuckets implements SharedBuckets {

    static final String KEY_PREFIX = "rate-limit:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET =
            RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    RedisBuckets(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitDecision consume(BucketKey key) {
        List<?> result = run(key, "consume");
        if ((Long) result.get(0) == 1) {
            return RateLimitDecision.permit();
        }
        long microsToWait = (Long) result.get(1);
        return RateLimitDecision.rejected(microsToWait * 1_000);
    }

    @Override
    public void refund(BucketKey key) {
        run(key, "refund");
    }

    @Override
    public void clear(BucketKey key) {
        redis.delete(redisKey(key));
    }

    @Override
    public void clearAll() {
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").build())) {
            keys.forEachRemaining(redis::delete);
        }
    }

    private List<?> run(BucketKey key, String operation) {
        return redis.execute(
                TOKEN_BUCKET,
                List.of(redisKey(key)),
                Long.toString(key.capacity()),
                Long.toString(key.window().toNanos() / 1_000),
                operation
        );
    }

    static String redisKey(BucketKey key) {
        return KEY_PREFIX + key.policyName()
                + ":" + key.capacity()
                + ":" + key.window().toMillis()
                + ":" + sha256(key.identity());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
