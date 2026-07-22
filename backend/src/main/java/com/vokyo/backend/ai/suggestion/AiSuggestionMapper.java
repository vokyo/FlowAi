package com.vokyo.backend.ai.suggestion;

import com.vokyo.backend.ai.suggestion.dto.AiSuggestionResponse;
import org.springframework.stereotype.Component;

@Component
public class AiSuggestionMapper {

    public AiSuggestionResponse toResponse(AiSuggestion suggestion) {
        return new AiSuggestionResponse(
                suggestion.getId(),
                suggestion.getType(),
                suggestion.getStatus(),
                suggestion.getProject().getId(),
                suggestion.getSourceIssue() == null
                        ? null
                        : suggestion.getSourceIssue().getId(),
                suggestion.getContent(),
                new AiSuggestionResponse.Metadata(
                        suggestion.getPromptVersion(),
                        suggestion.getCreatedAt(),
                        suggestion.isContextTruncated(),
                        suggestion.getProvider(),
                        suggestion.getModel(),
                        suggestion.getInputTokens(),
                        suggestion.getOutputTokens()
                ),
                suggestion.getCreatedIssueIds(),
                suggestion.getCreatedAt(),
                suggestion.getExpiresAt(),
                suggestion.getAppliedAt(),
                suggestion.getDismissedAt()
        );
    }
}
