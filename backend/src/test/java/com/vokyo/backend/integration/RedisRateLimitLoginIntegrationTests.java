package com.vokyo.backend.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Runs every login rate-limit scenario again with the buckets in Redis, the way a
 * deployment with more than one instance counts them.
 */
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy",
        "app.security.rate-limit.enabled=true",
        "app.redis.enabled=true"
})
class RedisRateLimitLoginIntegrationTests extends RateLimitLoginIntegrationTests {
}
