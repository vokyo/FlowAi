package com.vokyo.backend.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts requests in token buckets. With Redis on, every instance draws from the same
 * buckets there, so a limit holds however many instances run. Without Redis, or
 * while it does not answer in time, each instance counts in its own memory: the limit
 * still holds per instance, and the backend never waits on Redis or fails because of
 * it.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final long CLEANUP_MASK = 1023;
    private static final int EVICTION_HEADROOM_DIVISOR = 10;
    private static final long NEVER = Long.MIN_VALUE;
    private static final long SHARED_WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final RateLimitProperties properties;
    private final TimeMeter timeMeter;
    private final MeterRegistry meterRegistry;
    private final Optional<SharedBuckets> sharedBuckets;
    private final ConcurrentHashMap<BucketKey, BucketEntry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();
    private final AtomicLong lastSharedWarningNanos = new AtomicLong(NEVER);

    public RateLimitService(
            RateLimitProperties properties,
            TimeMeter timeMeter,
            MeterRegistry meterRegistry,
            Optional<SharedBuckets> sharedBuckets
    ) {
        this.properties = properties;
        this.timeMeter = timeMeter;
        this.meterRegistry = meterRegistry;
        this.sharedBuckets = sharedBuckets;
    }

    public RateLimitDecision consume(RateLimitPolicy policy, String identity) {
        return consume(
                policy.metricName(),
                policy.capacity(),
                policy.window(),
                identity
        );
    }

    public RateLimitDecision consume(
            String policyName,
            long capacity,
            Duration window,
            String identity
    ) {
        if (!properties.enabled()) {
            return RateLimitDecision.permit();
        }

        BucketKey key = new BucketKey(policyName, capacity, window, identity);
        RateLimitDecision decision = consumeShared(key).orElseGet(() -> consumeLocal(key));
        if (!decision.allowed()) {
            meterRegistry.counter("flowai.rate_limit.rejected", "policy", policyName).increment();
        }
        return decision;
    }

    public void clear(RateLimitPolicy policy, String identity) {
        BucketKey key = key(policy, identity);
        buckets.remove(key);
        sharedBuckets.ifPresent(shared -> {
            try {
                shared.clear(key);
            } catch (DataAccessException exception) {
                sharedBucketsUnavailable(exception);
            }
        });
    }

    public void refund(RateLimitPolicy policy, String identity) {
        BucketKey key = key(policy, identity);
        if (sharedBuckets.isPresent()) {
            try {
                sharedBuckets.get().refund(key);
                return;
            } catch (DataAccessException exception) {
                sharedBucketsUnavailable(exception);
            }
        }
        BucketEntry entry = buckets.get(key);
        if (entry != null) {
            entry.bucket().addTokens(1);
        }
    }

    /** Empties every bucket, the shared ones included, for tests that need a clean slate. */
    public void clearAll() {
        buckets.clear();
        sharedBuckets.ifPresent(SharedBuckets::clearAll);
    }

    /**
     * The decision of the bucket every instance shares, or none when there is no
     * shared bucket or Redis does not answer in time, so this instance counts alone.
     */
    private Optional<RateLimitDecision> consumeShared(BucketKey key) {
        if (sharedBuckets.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(sharedBuckets.get().consume(key));
        } catch (DataAccessException exception) {
            sharedBucketsUnavailable(exception);
            return Optional.empty();
        }
    }

    private RateLimitDecision consumeLocal(BucketKey key) {
        long now = timeMeter.currentTimeNanos();
        cleanupIfNeeded(now);
        BucketEntry entry = buckets.computeIfAbsent(
                key,
                ignored -> new BucketEntry(newBucket(key.capacity(), key.window()), new AtomicLong(now))
        );
        entry.lastAccessNanos().set(now);
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.permit();
        }
        return RateLimitDecision.rejected(probe.getNanosToWaitForRefill());
    }

    /**
     * Counts every request that fell back to this instance's buckets, but logs at most
     * once a minute: during an outage every rate-limited request lands here.
     */
    private void sharedBucketsUnavailable(DataAccessException exception) {
        meterRegistry.counter("flowai.rate_limit.shared_unavailable").increment();
        long now = timeMeter.currentTimeNanos();
        long last = lastSharedWarningNanos.get();
        boolean due = last == NEVER || now - last >= SHARED_WARNING_INTERVAL_NANOS;
        if (due && lastSharedWarningNanos.compareAndSet(last, now)) {
            log.warn("event=rate_limit_shared_buckets_unavailable reason={}", exception.toString());
        }
    }

    private BucketKey key(RateLimitPolicy policy, String identity) {
        return new BucketKey(
                policy.metricName(),
                policy.capacity(),
                policy.window(),
                identity
        );
    }

    private Bucket newBucket(long capacity, Duration window) {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(Bandwidth.simple(capacity, window))
                .build();
    }

    private void cleanupIfNeeded(long now) {
        long operation = operations.incrementAndGet();
        int maxEntries = properties.maxEntries();
        if ((operation & CLEANUP_MASK) != 0 && buckets.size() < maxEntries) {
            return;
        }

        long cutoff = now - properties.idleTtl().toNanos();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessNanos().get() < cutoff);

        // Evict down to a target below the cap rather than to exactly one slot under
        // it. Freeing a single slot per sweep means the next new identity refills the
        // map and pays for another full scan, so a request stream of distinct
        // identities turns every request into an O(entries) pass.
        int target = maxEntries - Math.max(1, maxEntries / EVICTION_HEADROOM_DIVISOR);
        int excess = buckets.size() - target;
        if (excess <= 0) {
            return;
        }

        buckets.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().lastAccessNanos().get()))
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(buckets::remove);
    }

    private record BucketEntry(Bucket bucket, AtomicLong lastAccessNanos) {
    }
}
