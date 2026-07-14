package com.vokyo.backend.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.Comparator;
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
        if (!properties.enabled()) {
            return RateLimitDecision.permit();
        }

        long now = timeMeter.currentTimeNanos();
        cleanupIfNeeded(now);
        BucketEntry entry = buckets.computeIfAbsent(
                new BucketKey(policy, identity),
                ignored -> new BucketEntry(newBucket(policy), new AtomicLong(now))
        );
        entry.lastAccessNanos().set(now);
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.permit();
        }

        meterRegistry.counter("flowai.rate_limit.rejected", "policy", policy.metricName()).increment();
        return RateLimitDecision.rejected(probe.getNanosToWaitForRefill());
    }

    public void clear(RateLimitPolicy policy, String identity) {
        buckets.remove(new BucketKey(policy, identity));
    }

    public void refund(RateLimitPolicy policy, String identity) {
        BucketEntry entry = buckets.get(new BucketKey(policy, identity));
        if (entry != null) {
            entry.bucket().addTokens(1);
        }
    }

    public void clearAll() {
        buckets.clear();
    }

    private Bucket newBucket(RateLimitPolicy policy) {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(Bandwidth.simple(policy.capacity(), policy.window()))
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

    private record BucketKey(RateLimitPolicy policy, String identity) {
    }

    private record BucketEntry(Bucket bucket, AtomicLong lastAccessNanos) {
    }
}
