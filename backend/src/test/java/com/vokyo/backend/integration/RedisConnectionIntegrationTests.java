package com.vokyo.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connects to a real Redis through the same properties every environment sets, and
 * checks what the Redis-backed features will rely on: a value can be written with an
 * expiry, and the client gives up after a few hundred milliseconds.
 */
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class RedisConnectionIntegrationTests {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Autowired
    private HealthContributorRegistry healthContributors;

    @Test
    void writesAValueThatExpiresOnItsOwn() {
        String key = "redis-connection-test:" + UUID.randomUUID();

        redis.opsForValue().set(key, "1", Duration.ofSeconds(30));

        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 30_000L);
    }

    @Test
    void waitsAFewHundredMillisecondsForRedisRatherThanLettucesMinute() {
        LettuceClientConfiguration client = connectionFactory.getClientConfiguration();

        assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(client.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void leavesRedisOutOfTheBackendsHealth() {
        assertThat(healthContributors.getContributor("redis")).isNull();
    }

}
