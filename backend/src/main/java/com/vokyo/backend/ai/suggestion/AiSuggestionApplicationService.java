package com.vokyo.backend.ai.suggestion;

import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.suggestion.dto.AiSuggestionResponse;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AiSuggestionApplicationService {

    private final WorkspaceAccessService workspaceAccessService;
    private final AiSuggestionService suggestionService;
    private final AiSuggestionMapper suggestionMapper;
    private final AiMetrics metrics;

    public AiSuggestionApplicationService(
            WorkspaceAccessService workspaceAccessService,
            AiSuggestionService suggestionService,
            AiSuggestionMapper suggestionMapper,
            AiMetrics metrics
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.suggestionService = suggestionService;
        this.suggestionMapper = suggestionMapper;
        this.metrics = metrics;
    }

    public AiSuggestionResponse get(Jwt jwt, UUID suggestionId) {
        CurrentWorkspaceContext context =
                workspaceAccessService.requireCurrentContext(jwt);
        AiSuggestion suggestion = suggestionService.getOwnedSuggestion(context, suggestionId);
        if (suggestion.getStatus() == AiSuggestionStatus.EXPIRED && metrics != null) {
            metrics.recordSuggestion(suggestion.getType(), suggestion.getStatus());
        }
        return suggestionMapper.toResponse(suggestion);
    }

    public AiSuggestionResponse dismiss(Jwt jwt, UUID suggestionId) {
        CurrentWorkspaceContext context =
                workspaceAccessService.requireCurrentContext(jwt);
        AiSuggestion suggestion = suggestionService.dismiss(context, suggestionId);
        if (metrics != null) {
            metrics.recordSuggestion(suggestion.getType(), suggestion.getStatus());
        }
        if (suggestion.getStatus() != AiSuggestionStatus.DISMISSED) {
            throw com.vokyo.backend.ai.AiFeatureException.suggestionNotDraft();
        }
        return suggestionMapper.toResponse(suggestion);
    }
}
