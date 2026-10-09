package com.vokyo.backend.integration;

import com.vokyo.backend.agent.AgentProperties;
import com.vokyo.backend.agent.AgentRunLock;
import com.vokyo.backend.ai.AiFeatureException;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run lock against a Redis of its own, which these tests pause to make it hang:
 * one lock per person and project, only its owner lets go of it, it outlives the
 * longest call to the agent, and when Redis hangs the work goes ahead and leaves no
 * lock behind once Redis answers again.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"spring.ai.openai.api-key=dummy", "app.redis.enabled=true"})
class AgentRunLockIntegrationTests {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Autowired private AgentRunLock lock;
    @Autowired private AgentProperties agentProperties;
    @Autowired private StringRedisTemplate redis;

    private final UUID person = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private boolean paused;

    @AfterEach
    void letRedisAnswerAgain() {
        if (paused) {
            unpause();
        }
    }

    @Test
    void thereIsOneLockPerPersonAndProject() {
        assertThat(lock.tryLock(person, project, "first")).isTrue();

        assertThat(lock.tryLock(person, project, "second")).isFalse();
        assertThat(lock.tryLock(person, UUID.randomUUID(), "second")).as("another project").isTrue();
        assertThat(lock.tryLock(UUID.randomUUID(), project, "second")).as("another person").isTrue();
    }

    @Test
    void theLockOutlivesTheLongestCallToTheAgent() {
        lock.tryLock(person, project, "first");

        long longestCall = agentProperties.connectTimeout().plus(agentProperties.readTimeout()).toMillis();
        assertThat(redis.getExpire(lockKey(), TimeUnit.MILLISECONDS)).isBetween(longestCall, longestCall + 10_000);
    }

    @Test
    void onlyTheOwnerLetsGoOfTheLock() {
        lock.tryLock(person, project, "first");

        lock.unlock(person, project, "second");
        assertThat(redis.opsForValue().get(lockKey())).isEqualTo("first");

        lock.unlock(person, project, "first");
        assertThat(redis.hasKey(lockKey())).isFalse();
    }

    @Test
    void workIsRefusedWhileTheLockIsHeldAndLetsGoWhenItFails() {
        lock.tryLock(person, project, "another-run");
        assertThatThrownBy(() -> lock.whileHeld(person, project, () -> "work"))
                .isInstanceOfSatisfying(AiFeatureException.class,
                        refused -> assertThat(refused.code()).isEqualTo("AI_AGENT_RUN_IN_PROGRESS"));
        lock.unlock(person, project, "another-run");

        assertThatThrownBy(() -> lock.whileHeld(person, project, () -> {
            throw new IllegalStateException("the agent failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(redis.hasKey(lockKey())).isFalse();
    }

    @Test
    void whileRedisHangsTheWorkGoesAheadAndLeavesNoLockOnceRedisAnswersAgain() throws InterruptedException {
        // Redis keeps the release script once it has run it; a hang before its first
        // run would leave the lock to expire on its own instead.
        lock.whileHeld(person, project, () -> "warm up");
        pause();

        long started = System.nanoTime();
        String done = lock.whileHeld(person, project, () -> "done");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(done).isEqualTo("done");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));

        // The lock that timed out is written once Redis answers again, and the
        // release sent after it on the same connection removes it.
        unpause();
        assertThat(lockGoneWithin(Duration.ofSeconds(5))).isTrue();
        assertThat(lock.tryLock(person, project, "next run")).isTrue();
    }

    @Test
    void withRedisOffThereIsNoLock() {
        AgentRunLock off = new AgentRunLock(redis, agentProperties, false);

        assertThat(off.tryLock(person, project, "first")).isTrue();
        assertThat(off.tryLock(person, project, "second")).isTrue();
        assertThat(redis.hasKey(lockKey())).isFalse();
    }

    private String lockKey() {
        return "agent-run-lock:" + person + ":" + project;
    }

    private boolean lockGoneWithin(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!Boolean.TRUE.equals(redis.hasKey(lockKey()))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        paused = true;
    }

    private void unpause() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        paused = false;
    }
}
