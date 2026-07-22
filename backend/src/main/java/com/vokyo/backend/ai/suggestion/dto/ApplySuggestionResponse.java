package com.vokyo.backend.ai.suggestion.dto;

import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApplySuggestionResponse(
        UUID suggestionId,
        AiSuggestionStatus status,
        List<UUID> createdIssueIds,
        Instant appliedAt
) {
}
