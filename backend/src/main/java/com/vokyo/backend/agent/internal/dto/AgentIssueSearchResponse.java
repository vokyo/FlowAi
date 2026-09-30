package com.vokyo.backend.agent.internal.dto;

import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueStatus;

import java.util.List;
import java.util.UUID;

public record AgentIssueSearchResponse(
        List<Item> items,
        boolean truncated
) {

    public record Item(
            UUID id,
            String title,
            IssueStatus status,
            IssuePriority priority,
            UUID assigneeUserId,
            String assigneeDisplayName
    ) {
    }
}
