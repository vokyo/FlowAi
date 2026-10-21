package com.vokyo.backend.integration;

import com.vokyo.backend.security.TokenVersionCache;
import com.vokyo.backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way a session ends, again with token versions kept in Redis, plus what the
 * cache itself must get right against a real Redis and real transactions: what it
 * stores and for how long, that only a committed raise reaches it, that a fresh
 * token survives a raise Redis never heard of, and that a hanging Redis slows down
 * token checks once, not on every request.
 */
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy",
        "app.redis.enabled=true",
        "app.security.token-version-cache.skip-after-failure=2s"
})
class RedisAccessTokenRevocationIntegrationTests extends AccessTokenRevocationIntegrationTests {

    private static final Duration SKIP_AFTER_FAILURE = Duration.ofSeconds(2);

    @Autowired private UserRepository users;
    @Autowired private TokenVersionCache tokenVersionCache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired @Qualifier("redisContainer") private GenericContainer<?> redisContainer;

    private boolean paused;

    @AfterEach
    void letRedisAnswerAgain() throws InterruptedException {
        if (paused) {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
            paused = false;
            // Let the pause the failure started run out, so the next test reads Redis.
            Thread.sleep(SKIP_AFTER_FAILURE.plusMillis(200).toMillis());
        }
    }

    @Test
    void aCheckedTokenLeavesItsUsersVersionInRedisForFiveMinutes() throws Exception {
        Session session = register("cached");

        assertWorks(session.accessToken());

        UUID userId = userId(session);
        assertThat(cachedVersion(userId)).isEqualTo("0");
        assertThat(redis.getExpire(key(userId), TimeUnit.SECONDS)).isBetween(1L, 300L);
    }

    @Test
    void theScriptKeepsTheGreaterVersionAndAnOlderWriteDoesNotExtendTheEntry() {
        RedisScript<Long> keepGreater =
                RedisScript.of(new ClassPathResource("redis/token-version-max.lua"), Long.class);
        String key = key(UUID.randomUUID());

        assertThat(redis.execute(keepGreater, List.of(key), "4", "60000")).isEqualTo(1L);
        assertThat(redis.execute(keepGreater, List.of(key), "3", "600000")).isZero();
        assertThat(redis.execute(keepGreater, List.of(key), "4", "600000")).isZero();
        assertThat(redis.opsForValue().get(key)).isEqualTo("4");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60_000L);

        assertThat(redis.execute(keepGreater, List.of(key), "5", "600000")).isEqualTo(1L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("5");
    }

    @Test
    void aRolledBackRevocationLeavesTheCacheAndTheTokenAsTheyWere() throws Exception {
        Session session = register("rolled-back");
        assertWorks(session.accessToken());
        UUID userId = userId(session);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tokenVersionCache.revokeAccessTokens(userId);
            status.setRollbackOnly();
        });

        assertThat(users.findTokenVersionById(userId)).contains(0);
        assertThat(cachedVersion(userId)).isEqualTo("0");
        assertWorks(session.accessToken());
    }

    @Test
    void aFreshTokenWorksAfterARaiseRedisNeverHeardOfAndRepairsTheEntry() throws Exception {
        Session session = register("missed-write");
        assertWorks(session.accessToken());
        UUID userId = userId(session);

        // A logout whose write to Redis was lost: only the database has the raise.
        new TransactionTemplate(transactions).executeWithoutResult(status -> users.revokeAccessTokens(userId));
        assertThat(cachedVersion(userId)).isEqualTo("0");

        Session fresh = login(session.email());
        assertWorks(fresh.accessToken());

        assertThat(cachedVersion(userId)).isEqualTo("1");
        assertRefused(session.accessToken());
    }

    @Test
    void whileRedisHangsOnlyTheFirstTokenCheckWaitsForIt() throws Exception {
        Session session = register("hanging");
        assertWorks(session.accessToken());

        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;
        long started = System.nanoTime();
        for (int request = 0; request < 10; request++) {
            assertWorks(session.accessToken());
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Waiting out the 300 ms Redis timeout on all ten would take three seconds.
        assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
    }

    private UUID userId(Session session) {
        return users.findByEmail(session.email()).orElseThrow().getId();
    }

    private String cachedVersion(UUID userId) {
        return redis.opsForValue().get(key(userId));
    }

    private static String key(UUID userId) {
        return "token-version:" + userId;
    }
}
