package com.vokyo.backend.issue.dto;

public record IssueWatchResponse(
    long watcherCount,
    boolean watched
) {
}
