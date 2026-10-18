package com.vokyo.backend.security;

import com.vokyo.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Keeps each user's token version in Redis, so that checking an access token need
 * not read the users table on every request. The database stays the source of
 * truth: Redis only ever holds a version read from it or committed to it.
 *
 * Nothing uses this yet. Token checking and logout switch to it together, once
 * reading and writing are both in place; one without the other would let a
 * logged-out token keep working.
 */
@Component
public class TokenVersionCache {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionCache.class);
    private static final RedisScript<Long> KEEP_GREATER =
            RedisScript.of(new ClassPathResource("redis/token-version-max.lua"), Long.class);
    private static final long BREAKER_CLOSED = Long.MIN_VALUE;

    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private final TokenVersionCacheProperties properties;
    private final boolean enabled;
    private final LongSupplier nanoTime;
    // Until when, by nanoTime, this instance reads the database without asking Redis.
    private final AtomicLong skipRedisUntil = new AtomicLong(BREAKER_CLOSED);

    @Autowired
    public TokenVersionCache(
            UserRepository userRepository,
            StringRedisTemplate redis,
            TokenVersionCacheProperties properties,
            @Value("${app.redis.enabled}") boolean enabled
    ) {
        this(userRepository, redis, properties, enabled, System::nanoTime);
    }

    TokenVersionCache(
            UserRepository userRepository,
            StringRedisTemplate redis,
            TokenVersionCacheProperties properties,
            boolean enabled,
            LongSupplier nanoTime
    ) {
        this.userRepository = userRepository;
        this.redis = redis;
        this.properties = properties;
        this.enabled = enabled;
        this.nanoTime = nanoTime;
    }

    /** The user's current token version, or empty when there is no such user. */
    public Optional<Integer> currentVersion(UUID userId) {
        if (skipRedis()) {
            return userRepository.findTokenVersionById(userId);
        }
        try {
            String cached = redis.opsForValue().get(key(userId));
            if (cached != null) {
                return Optional.of(Integer.parseInt(cached));
            }
        } catch (DataAccessException | NumberFormatException exception) {
            // A value that is not a number is one bad entry, not a Redis in trouble.
            if (exception instanceof DataAccessException) {
                openBreaker();
            }
            log.warn("event=token_version_cache_read_failed reason={}", exception.toString());
            return userRepository.findTokenVersionById(userId);
        }
        Optional<Integer> fromDatabase = userRepository.findTokenVersionById(userId);
        fromDatabase.ifPresent(version -> cacheVersion(userId, version));
        return fromDatabase;
    }

    /**
     * Stores a version unless Redis already holds a greater one, for the configured
     * TTL. A failure is logged and swallowed, and pauses reads from Redis like a
     * failed read does: the caller already has the version, and the next read goes
     * to the database again.
     */
    private void cacheVersion(UUID userId, int version) {
        try {
            redis.execute(
                    KEEP_GREATER,
                    List.of(key(userId)),
                    Integer.toString(version),
                    Long.toString(properties.ttl().toMillis())
            );
        } catch (DataAccessException exception) {
            openBreaker();
            log.warn("event=token_version_cache_write_failed reason={}", exception.toString());
        }
    }

    /**
     * Whether reads should go straight to the database: Redis is switched off, or it
     * failed less than skip-after-failure ago. Without the pause, every request would
     * wait out the Redis timeout before reading the database while Redis hangs.
     */
    private boolean skipRedis() {
        if (!enabled) {
            return true;
        }
        long until = skipRedisUntil.get();
        return until != BREAKER_CLOSED && nanoTime.getAsLong() - until < 0;
    }

    /**
     * Leaves Redis alone on this instance for skip-after-failure. Each instance keeps
     * its own pause; the first read after it asks Redis again.
     */
    private void openBreaker() {
        skipRedisUntil.set(nanoTime.getAsLong() + properties.skipAfterFailure().toNanos());
    }

    static String key(UUID userId) {
        return "token-version:" + userId;
    }
}
