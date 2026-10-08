package com.vokyo.backend.integration;

import com.vokyo.backend.security.ratelimit.RateLimitDecision;
import com.vokyo.backend.security.ratelimit.RateLimitPolicy;
import com.vokyo.backend.security.ratelimit.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter while its Redis hangs. A paused container still accepts the
 * connection and answers nothing, the case a short timeout exists for: the limit
 * keeps holding, now on this instance alone, no request waits more than a moment,
 * and counting moves back to Redis once it answers again.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy",
        "app.security.rate-limit.enabled=true",
        "app.redis.enabled=true"
})
class RedisRateLimitFallbackIntegrationTests {

    private static final RateLimitPolicy POLICY = RateLimitPolicy.LOGIN_EMAIL_FAILURE;

    /** This class's own Redis, since pausing a shared one would stall other tests. */
    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StringRedisTemplate redis;

    private boolean paused;

    @BeforeEach
    void startWithEmptyBuckets() {
        rateLimitService.clearAll();
    }

    @AfterEach
    void letRedisAnswerAgain() {
        if (paused) {
            unpause();
        }
    }

    @Test
    void whileRedisHangsTheLimitHoldsOnThisInstanceAndNoRequestWaitsLong() {
        String client = UUID.randomUUID() + "@example.com";
        pause();

        for (long attempt = 1; attempt <= POLICY.capacity(); attempt++) {
            Timed timed = consume(client);
            assertThat(timed.decision().allowed()).as("attempt %s", attempt).isTrue();
            assertThat(timed.elapsed()).isLessThan(Duration.ofSeconds(1));
        }
        Timed rejected = consume(client);
        assertThat(rejected.decision().allowed()).isFalse();
        assertThat(rejected.elapsed()).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void countingMovesBackToRedisOnceItAnswersAgain() {
        String client = UUID.randomUUID() + "@example.com";
        pause();
        Timed whilePaused = consume(client);
        assertThat(whilePaused.decision().allowed()).isTrue();
        assertThat(whilePaused.elapsed()).isLessThan(Duration.ofSeconds(1));

        unpause();

        assertThat(consume(client).decision().allowed()).isTrue();
        assertThat(redisKeys()).as("the bucket is in Redis again").isEqualTo(1);
    }

    private Timed consume(String client) {
        long started = System.nanoTime();
        RateLimitDecision decision = rateLimitService.consume(POLICY, client);
        return new Timed(decision, Duration.ofNanos(System.nanoTime() - started));
    }

    private long redisKeys() {
        Long size = redis.execute(connection -> connection.serverCommands().dbSize(), true);
        return size == null ? 0 : size;
    }

    private void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        paused = true;
    }

    private void unpause() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        paused = false;
    }

    private record Timed(RateLimitDecision decision, Duration elapsed) {
    }

}
