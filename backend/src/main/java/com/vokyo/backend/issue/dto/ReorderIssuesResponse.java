package com.vokyo.backend.issue.dto;

import java.util.UUID;

public record ReorderIssuesResponse(
        UUID issueId,
        UUID workflowStateId,
        long boardPosition,
        boolean rebalanced
) {
}
