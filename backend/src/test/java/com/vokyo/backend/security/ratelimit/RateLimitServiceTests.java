package com.vokyo.backend.security.ratelimit;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTests {

    private MutableTimeMeter timeMeter;
    private SimpleMeterRegistry meterRegistry;
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        timeMeter = new MutableTimeMeter();
        meterRegistry = new SimpleMeterRegistry();
        rateLimitService = new RateLimitService(
                new RateLimitProperties(true, 1_000, Duration.ofHours(2)),
                timeMeter,
                meterRegistry
        );
    }

    @ParameterizedTest
    @EnumSource(RateLimitPolicy.class)
    void eachPolicyAllowsItsCapacityThenRejectsAndRecoversWithoutSleeping(RateLimitPolicy policy) {
        String identity = "primary";

        for (long attempt = 1; attempt <= policy.capacity(); attempt++) {
            assertThat(rateLimitService.consume(policy, identity).allowed())
                    .as("attempt %s of %s for %s", attempt, policy.capacity(), policy)
                    .isTrue();
        }

        RateLimitDecision rejected = rateLimitService.consume(policy, identity);
        long secondsPerToken = policy.window().toSeconds() / policy.capacity();

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(secondsPerToken);
        assertThat(meterRegistry.counter(
                "flowai.rate_limit.rejected",
                "policy",
                policy.metricName()
        ).count()).isEqualTo(1.0);

        timeMeter.advance(Duration.ofSeconds(secondsPerToken).minusNanos(1));
        RateLimitDecision justBeforeRefill = rateLimitService.consume(policy, identity);
        assertThat(justBeforeRefill.allowed()).isFalse();
        assertThat(justBeforeRefill.retryAfterSeconds()).isEqualTo(1);

        timeMeter.advance(Duration.ofNanos(1));
        assertThat(rateLimitService.consume(policy, identity).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RateLimitPolicy.class)
    void bucketsAreIsolatedByIdentity(RateLimitPolicy policy) {
        for (long attempt = 0; attempt < policy.capacity(); attempt++) {
            assertThat(rateLimitService.consume(policy, "identity-a").allowed()).isTrue();
        }

        assertThat(rateLimitService.consume(policy, "identity-a").allowed()).isFalse();
        assertThat(rateLimitService.consume(policy, "identity-b").allowed()).isTrue();
    }

    @Test
    void disabledRateLimitingNeverCreatesAnEffectiveLimit() {
        RateLimitService disabled = new RateLimitService(
                new RateLimitProperties(false, 1_000, Duration.ofHours(2)),
                timeMeter,
                meterRegistry
        );

        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(disabled.consume(RateLimitPolicy.REGISTRATION_IP, "identity").allowed()).isTrue();
        }
        assertThat(meterRegistry.find("flowai.rate_limit.rejected").counter()).isNull();
    }

    private static final class MutableTimeMeter implements TimeMeter {

        private final AtomicLong currentNanos = new AtomicLong(Duration.ofDays(1).toNanos());

        @Override
        public long currentTimeNanos() {
            return currentNanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            currentNanos.addAndGet(duration.toNanos());
        }
    }
}
