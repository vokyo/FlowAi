package com.vokyo.backend.ai.summary.issue.dto;

public record IssueSummaryRequest(
        Boolean includeComments,
        Boolean includeActivity
) {
    public boolean commentsEnabled() {
        return !Boolean.FALSE.equals(includeComments);
    }

    public boolean activityEnabled() {
        return !Boolean.FALSE.equals(includeActivity);
    }
}
