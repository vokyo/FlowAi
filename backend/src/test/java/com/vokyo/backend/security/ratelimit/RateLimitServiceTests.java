package com.vokyo.backend.security.ratelimit;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class RateLimitServiceTests {

    private static final RateLimitPolicy POLICY = RateLimitPolicy.LOGIN_EMAIL_FAILURE;
    private static final String SHARED_WARNING = "event=rate_limit_shared_buckets_unavailable";

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
                meterRegistry,
                Optional.empty()
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
                meterRegistry,
                Optional.empty()
        );

        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(disabled.consume(RateLimitPolicy.REGISTRATION_IP, "identity").allowed()).isTrue();
        }
        assertThat(meterRegistry.find("flowai.rate_limit.rejected").counter()).isNull();
    }

    @Test
    void withoutSharedBucketsEveryInstanceAllowsTheWholeLimit() {
        RateLimitService otherInstance = instance(Optional.empty());

        assertThat(allowedAcross(rateLimitService, otherInstance, 2 * POLICY.capacity()))
                .isEqualTo(2 * POLICY.capacity());
    }

    @Test
    void instancesDrawingFromTheSameSharedBucketsShareOneLimit() {
        SharedBucketsStandIn redis = new SharedBucketsStandIn();
        RateLimitService oneInstance = instance(Optional.of(redis));
        RateLimitService otherInstance = instance(Optional.of(redis));

        assertThat(allowedAcross(oneInstance, otherInstance, 2 * POLICY.capacity()))
                .isEqualTo(POLICY.capacity());
        assertThat(meterRegistry.counter("flowai.rate_limit.rejected", "policy", POLICY.metricName()).count())
                .isEqualTo(POLICY.capacity());
    }

    @Test
    void whileTheSharedBucketsDoNotAnswerTheLimitHoldsOnThisInstance() {
        SharedBucketsStandIn redis = new SharedBucketsStandIn();
        RateLimitService service = instance(Optional.of(redis));
        redis.answering = false;

        for (long attempt = 1; attempt <= POLICY.capacity(); attempt++) {
            assertThat(service.consume(POLICY, "primary").allowed()).isTrue();
        }
        assertThat(service.consume(POLICY, "primary").allowed()).isFalse();
        assertThat(meterRegistry.counter("flowai.rate_limit.shared_unavailable").count())
                .isEqualTo(POLICY.capacity() + 1);

        redis.answering = true;
        assertThat(service.consume(POLICY, "primary").allowed())
                .as("Redis answers again, and its bucket is still full")
                .isTrue();
        assertThat(redis.taken).containsEntry(key(POLICY, "primary"), 1L);
    }

    @Test
    void warnsAboutTheSharedBucketsAtMostOnceAMinute(CapturedOutput output) {
        SharedBucketsStandIn redis = new SharedBucketsStandIn();
        RateLimitService service = instance(Optional.of(redis));
        redis.answering = false;

        for (int attempt = 0; attempt < 3; attempt++) {
            service.consume(POLICY, "primary");
        }
        timeMeter.advance(Duration.ofSeconds(59));
        service.consume(POLICY, "primary");
        assertThat(occurrences(output.getAll(), SHARED_WARNING)).isEqualTo(1);

        timeMeter.advance(Duration.ofSeconds(1));
        service.consume(POLICY, "primary");
        assertThat(occurrences(output.getAll(), SHARED_WARNING)).isEqualTo(2);
    }

    @Test
    void clearEmptiesTheSharedBucketAndThisInstancesOwn() {
        SharedBucketsStandIn redis = new SharedBucketsStandIn();
        RateLimitService service = instance(Optional.of(redis));
        redis.answering = false;
        for (long attempt = 0; attempt <= POLICY.capacity(); attempt++) {
            service.consume(POLICY, "primary");
        }
        redis.answering = true;
        service.consume(POLICY, "primary");

        service.clear(POLICY, "primary");

        assertThat(redis.taken).doesNotContainKey(key(POLICY, "primary"));
        redis.answering = false;
        assertThat(service.consume(POLICY, "primary").allowed())
                .as("this instance's bucket starts full again too")
                .isTrue();
    }

    @Test
    void aRefundGoesToTheSharedBucketOrToThisInstancesOwnWhileTheSharedOneIsDown() {
        SharedBucketsStandIn redis = new SharedBucketsStandIn();
        RateLimitService service = instance(Optional.of(redis));
        for (long attempt = 0; attempt < POLICY.capacity(); attempt++) {
            service.consume(POLICY, "primary");
        }

        service.refund(POLICY, "primary");

        assertThat(redis.taken).containsEntry(key(POLICY, "primary"), POLICY.capacity() - 1);

        redis.answering = false;
        for (long attempt = 0; attempt < POLICY.capacity(); attempt++) {
            service.consume(POLICY, "primary");
        }
        assertThat(service.consume(POLICY, "primary").allowed()).isFalse();
        service.refund(POLICY, "primary");
        assertThat(service.consume(POLICY, "primary").allowed()).isTrue();
    }

    private RateLimitService instance(Optional<SharedBuckets> sharedBuckets) {
        return new RateLimitService(
                new RateLimitProperties(true, 1_000, Duration.ofHours(2)),
                timeMeter,
                meterRegistry,
                sharedBuckets
        );
    }

    /** Alternates the attempts between two instances, as a load balancer would. */
    private static long allowedAcross(RateLimitService one, RateLimitService other, long attempts) {
        long allowed = 0;
        for (long attempt = 0; attempt < attempts; attempt++) {
            RateLimitService instance = attempt % 2 == 0 ? one : other;
            if (instance.consume(POLICY, "shared@example.com").allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    private static BucketKey key(RateLimitPolicy policy, String identity) {
        return new BucketKey(policy.metricName(), policy.capacity(), policy.window(), identity);
    }

    private static int occurrences(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    /**
     * Counts tokens the way Redis would for every instance that shares it, and can be
     * told to stop answering, as Redis does when it times out.
     */
    private static final class SharedBucketsStandIn implements SharedBuckets {

        private final Map<BucketKey, Long> taken = new ConcurrentHashMap<>();
        private volatile boolean answering = true;

        @Override
        public RateLimitDecision consume(BucketKey key) {
            answerOrTimeOut();
            long count = taken.merge(key, 1L, Long::sum);
            if (count <= key.capacity()) {
                return RateLimitDecision.permit();
            }
            taken.put(key, key.capacity());
            return RateLimitDecision.rejected(Duration.ofSeconds(1).toNanos());
        }

        @Override
        public void refund(BucketKey key) {
            answerOrTimeOut();
            taken.computeIfPresent(key, (ignored, count) -> count - 1);
        }

        @Override
        public void clear(BucketKey key) {
            answerOrTimeOut();
            taken.remove(key);
        }

        @Override
        public void clearAll() {
            answerOrTimeOut();
            taken.clear();
        }

        private void answerOrTimeOut() {
            if (!answering) {
                throw new QueryTimeoutException("Redis did not answer in time");
            }
        }
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
