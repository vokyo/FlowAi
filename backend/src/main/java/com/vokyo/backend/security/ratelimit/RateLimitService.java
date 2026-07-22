package com.vokyo.backend.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RateLimitService {

    private static final long CLEANUP_MASK = 1023;

    private final RateLimitProperties properties;
    private final TimeMeter timeMeter;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<BucketKey, BucketEntry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    public RateLimitService(
            RateLimitProperties properties,
            TimeMeter timeMeter,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.timeMeter = timeMeter;
        this.meterRegistry = meterRegistry;
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

        long now = timeMeter.currentTimeNanos();
        cleanupIfNeeded(now);
        BucketEntry entry = buckets.computeIfAbsent(
                new BucketKey(policyName, capacity, window, identity),
                ignored -> new BucketEntry(newBucket(capacity, window), new AtomicLong(now))
        );
        entry.lastAccessNanos().set(now);
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.permit();
        }

        meterRegistry.counter("flowai.rate_limit.rejected", "policy", policyName).increment();
        return RateLimitDecision.rejected(probe.getNanosToWaitForRefill());
    }

    public void clear(RateLimitPolicy policy, String identity) {
        buckets.remove(key(policy, identity));
    }

    public void refund(RateLimitPolicy policy, String identity) {
        BucketEntry entry = buckets.get(key(policy, identity));
        if (entry != null) {
            entry.bucket().addTokens(1);
        }
    }

    public void clearAll() {
        buckets.clear();
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
        if ((operation & CLEANUP_MASK) != 0 && buckets.size() < properties.maxEntries()) {
            return;
        }

        long cutoff = now - properties.idleTtl().toNanos();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessNanos().get() < cutoff);
        while (buckets.size() >= properties.maxEntries()) {
            buckets.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastAccessNanos().get()))
                    .map(Map.Entry::getKey)
                    .ifPresentOrElse(buckets::remove, () -> buckets.clear());
        }
    }

    private record BucketKey(
            String policyName,
            long capacity,
            Duration window,
            String identity
    ) {
    }

    private record BucketEntry(Bucket bucket, AtomicLong lastAccessNanos) {
    }
}
