package com.vokyo.backend.issue.dto;

import java.util.UUID;

public record IssueWatchResponse(
    long watcherCount,
    boolean watched
) {
}
