package com.vokyo.backend.ai.suggestion.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiSuggestionResponse(
        UUID id,
        AiSuggestionType type,
        AiSuggestionStatus status,
        UUID projectId,
        UUID sourceIssueId,
        JsonNode content,
        Metadata metadata,
        List<UUID> createdIssueIds,
        Instant createdAt,
        Instant expiresAt,
        Instant appliedAt,
        Instant dismissedAt
) {

    public record Metadata(
            String promptVersion,
            Instant generatedAt,
            boolean contextTruncated,
            String provider,
            String model,
            Integer inputTokens,
            Integer outputTokens
    ) {
    }
}
