package com.vokyo.backend.agent;

import com.vokyo.backend.ai.AiFeatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lets one person run the planning agent on one project at a time, across every
 * backend instance: a second start or revision while one is under way is refused
 * instead of paying for the model twice.
 *
 * The lock is a Redis key per person and project, whose value says who holds it. It
 * guards money, not data, since a duplicate run only leaves an extra plan to cancel.
 * So when Redis does not answer, the run goes ahead without it, and with Redis off
 * there is no lock at all.
 */
@Component
public class AgentRunLock {

    private static final Logger log = LoggerFactory.getLogger(AgentRunLock.class);
    private static final RedisScript<Long> RELEASE =
            RedisScript.of(new ClassPathResource("redis/release-lock.lua"), Long.class);
    private static final Duration MARGIN = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;

    public AgentRunLock(
            StringRedisTemplate redis,
            AgentProperties properties,
            @Value("${app.redis.enabled}") boolean enabled
    ) {
        this.redis = redis;
        this.enabled = enabled;
        // Longer than the longest call to the agent, so a lock cannot run out while
        // its run is still going.
        this.ttl = properties.connectTimeout().plus(properties.readTimeout()).plus(MARGIN);
    }

    /**
     * Does the work while holding this person's lock on the project, and lets go
     * afterwards however the work ends. Refuses with 409 when another of their runs
     * holds it. It lets go even when taking the lock timed out and the work went
     * ahead without it: a timed-out write may still reach Redis later, and the
     * release, sent after it on the same connection, then removes it.
     */
    public <T> T whileHeld(UUID userId, UUID projectId, Supplier<T> work) {
        String owner = UUID.randomUUID().toString();
        if (!tryLock(userId, projectId, owner)) {
            throw AiFeatureException.agentRunInProgress();
        }
        try {
            return work.get();
        } finally {
            unlock(userId, projectId, owner);
        }
    }

    /**
     * Takes the lock for this person and project. True when the run may go ahead:
     * the lock was free, or Redis could not be asked. False when another run of
     * theirs holds it.
     */
    public boolean tryLock(UUID userId, UUID projectId, String owner) {
        if (!enabled) {
            return true;
        }
        try {
            Boolean written = redis.opsForValue().setIfAbsent(key(userId, projectId), owner, ttl);
            return Boolean.TRUE.equals(written);
        } catch (DataAccessException exception) {
            log.warn("event=agent_run_lock_unavailable reason={}", exception.toString());
            return true;
        }
    }

    /** Lets go of the lock, but only if this owner still holds it. */
    public void unlock(UUID userId, UUID projectId, String owner) {
        if (!enabled) {
            return;
        }
        try {
            redis.execute(RELEASE, List.of(key(userId, projectId)), owner);
        } catch (DataAccessException exception) {
            log.warn("event=agent_run_lock_not_released reason={}", exception.toString());
        }
    }

    static String key(UUID userId, UUID projectId) {
        return "agent-run-lock:" + userId + ":" + projectId;
    }
}
