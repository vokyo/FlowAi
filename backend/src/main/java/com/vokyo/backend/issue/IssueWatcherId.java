package com.vokyo.backend.issue;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class IssueWatcherId implements Serializable {
    private UUID issueId;
    private UUID userId;

    public IssueWatcherId() {
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        IssueWatcherId that = (IssueWatcherId) o;
        return Objects.equals(issueId, that.issueId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issueId, userId);
    }

    public IssueWatcherId(UUID issueId, UUID userId) {
        this.issueId = issueId;
        this.userId = userId;
    }
}
