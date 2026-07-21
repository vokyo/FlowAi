package com.vokyo.backend.ai.breakdown.dto;

import com.vokyo.backend.ai.breakdown.IssueBreakdownResult;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;

import java.time.Instant;
import java.util.UUID;

public record IssueBreakdownSuggestionResponse(
        UUID id,
        AiSuggestionType type,
        AiSuggestionStatus status,
        UUID projectId,
        UUID sourceIssueId,
        IssueBreakdownResult content,
        Metadata metadata,
        Instant createdAt,
        Instant expiresAt
) {

    public record Metadata(
            String promptVersion,
            Instant generatedAt,
            boolean contextTruncated
    ) {
    }
}
