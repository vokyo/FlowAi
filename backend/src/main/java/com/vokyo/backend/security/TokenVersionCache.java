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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
     * The user's current token version, for checking a token issued under
     * issuedVersion. A cached version can only fall behind the database, never run
     * ahead of it, and it falls behind when a revocation's write to Redis failed. A
     * token newer than the cached version is then the sign: without a second look,
     * the user's fresh tokens would be refused until the stale entry expired. The
     * database settles it and the entry is repaired. The token's own version is
     * never the answer, as the user may have ended their sessions again since.
     */
    public Optional<Integer> currentVersion(UUID userId, int issuedVersion) {
        Optional<Integer> current = currentVersion(userId);
        if (current.isEmpty() || current.get() >= issuedVersion) {
            return current;
        }
        Optional<Integer> fromDatabase = userRepository.findTokenVersionById(userId);
        fromDatabase.ifPresent(version -> {
            log.info("event=token_version_cache_repaired userId={} cached={} current={}",
                    userId, current.get(), version);
            if (!skipRedis()) {
                cacheVersion(userId, version);
            }
        });
        return fromDatabase;
    }

    /**
     * Ends every access token the user holds by raising their version, and once the
     * caller's transaction commits, stores the raised version in Redis so that token
     * checks see it at once. A rolled-back raise never reaches Redis: a version that
     * is not committed would refuse the user's valid tokens.
     *
     * Must run inside the transaction that raises the version. The raised version is
     * read in that transaction, so nothing touches the database after the commit.
     * The write is tried even while reads leave Redis alone: of all writes, missing
     * this one is what lets an ended token keep working until the entry expires.
     */
    public void revokeAccessTokens(UUID userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Revoking access tokens needs a transaction to publish the new version after");
        }
        userRepository.revokeAccessTokens(userId);
        if (!enabled) {
            return;
        }
        userRepository.findTokenVersionById(userId).ifPresent(raised ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cacheVersion(userId, raised);
                    }
                }));
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
