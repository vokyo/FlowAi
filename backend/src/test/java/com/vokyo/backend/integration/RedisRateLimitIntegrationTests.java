package com.vokyo.backend.integration;

import com.vokyo.backend.security.ratelimit.RateLimitDecision;
import com.vokyo.backend.security.ratelimit.RateLimitPolicy;
import com.vokyo.backend.security.ratelimit.RateLimitProperties;
import com.vokyo.backend.security.ratelimit.RateLimitService;
import com.vokyo.backend.security.ratelimit.SharedBuckets;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter with Redis on, against a real Redis: every instance shares one
 * limit, a bucket lives in Redis only until it would be full again, and tokens come
 * back with time the way they do in memory.
 */
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy",
        "app.security.rate-limit.enabled=true",
        "app.redis.enabled=true"
})
class RedisRateLimitIntegrationTests {

    private static final RateLimitPolicy POLICY = RateLimitPolicy.LOGIN_EMAIL_FAILURE;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RateLimitProperties properties;

    @Autowired
    private SharedBuckets sharedBuckets;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void startWithEmptyBuckets() {
        rateLimitService.clearAll();
    }

    @Test
    void twoInstancesShareOneLimit() {
        RateLimitService otherInstance = new RateLimitService(
                properties,
                TimeMeter.SYSTEM_NANOTIME,
                new SimpleMeterRegistry(),
                Optional.of(sharedBuckets)
        );

        long allowed = 0;
        for (long attempt = 0; attempt < 2 * POLICY.capacity(); attempt++) {
            RateLimitService instance = attempt % 2 == 0 ? rateLimitService : otherInstance;
            if (instance.consume(POLICY, "shared@example.com").allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(POLICY.capacity());
    }

    @Test
    void keepsABucketInRedisOnlyUntilItWouldBeFullAgain() {
        rateLimitService.consume(RateLimitPolicy.LOGIN_IP, "192.0.2.1");

        List<String> keys = keys("rate-limit:login_ip:*");
        assertThat(keys).hasSize(1);
        String key = keys.getFirst();
        assertThat(key).as("the client is hashed, not stored").doesNotContain("192.0.2.1");

        Map<Object, Object> bucket = redis.opsForHash().entries(key);
        assertThat(Double.parseDouble((String) bucket.get("tokens"))).isEqualTo(19.0);
        assertThat(bucket).containsKey("at");
        // 20 tokens a minute: the one taken comes back within 3 seconds.
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 3_000L);
    }

    @Test
    void tokensComeBackWithTimeAndARejectionSaysHowLongToWait() throws InterruptedException {
        String client = UUID.randomUUID().toString();
        // Two a second: a token comes back every half second.
        assertThat(consumeTwoASecond(client).allowed()).isTrue();
        assertThat(consumeTwoASecond(client).allowed()).isTrue();

        RateLimitDecision rejected = consumeTwoASecond(client);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);

        Thread.sleep(700);
        assertThat(consumeTwoASecond(client).allowed()).isTrue();
    }

    @Test
    void clearingABucketStartsItFullAgain() {
        exhaust("cleared@example.com");

        rateLimitService.clear(POLICY, "cleared@example.com");

        assertThat(rateLimitService.consume(POLICY, "cleared@example.com").allowed()).isTrue();
    }

    @Test
    void aRefundGivesOneTokenBackAndNeverMoreThanTheCapacity() {
        rateLimitService.refund(POLICY, "refunded@example.com");
        assertThat(keys("rate-limit:" + POLICY.metricName() + ":*"))
                .as("a full bucket needs no key, and a refund cannot overfill it")
                .isEmpty();

        exhaust("refunded@example.com");
        rateLimitService.refund(POLICY, "refunded@example.com");

        assertThat(rateLimitService.consume(POLICY, "refunded@example.com").allowed()).isTrue();
        assertThat(rateLimitService.consume(POLICY, "refunded@example.com").allowed()).isFalse();
    }

    private RateLimitDecision consumeTwoASecond(String client) {
        return rateLimitService.consume("two_a_second", 2, Duration.ofSeconds(1), client);
    }

    private void exhaust(String client) {
        for (long attempt = 0; attempt < POLICY.capacity(); attempt++) {
            assertThat(rateLimitService.consume(POLICY, client).allowed()).isTrue();
        }
        assertThat(rateLimitService.consume(POLICY, client).allowed()).isFalse();
    }

    private List<String> keys(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

}
