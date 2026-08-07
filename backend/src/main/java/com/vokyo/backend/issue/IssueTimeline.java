package com.vokyo.backend.issue;

import java.time.Instant;

/**
 * When an issue was opened and, if it is finished, when it was completed.
 *
 * <p>{@link #systemTime()} is the normal case: the issue happens now, and the
 * entity stamps itself. {@link #backdated} carries history that predates the row —
 * an import from another tracker, or a seeded dataset — so that the analytics
 * completion trend and the issue list cursor see the dates the work actually has
 * rather than the moment it was written.
 */
public record IssueTimeline(Instant createdAt, Instant completedAt) {

    private static final IssueTimeline SYSTEM_TIME = new IssueTimeline(null, null);

    public IssueTimeline {
        if (createdAt == null && completedAt != null) {
            throw new IllegalArgumentException("completedAt requires a createdAt");
        }
        if (createdAt != null && completedAt != null && completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("completedAt cannot precede createdAt");
        }
    }

    public static IssueTimeline systemTime() {
        return SYSTEM_TIME;
    }

    public static IssueTimeline backdated(Instant createdAt, Instant completedAt) {
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required to backdate an issue");
        }
        return new IssueTimeline(createdAt, completedAt);
    }

    public boolean isBackdated() {
        return createdAt != null;
    }
}
