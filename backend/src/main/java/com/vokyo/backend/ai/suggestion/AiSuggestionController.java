package com.vokyo.backend.ai.suggestion;

import com.vokyo.backend.ai.suggestion.dto.AiSuggestionResponse;
import com.vokyo.backend.ai.suggestion.dto.ApplyIssueBreakdownRequest;
import com.vokyo.backend.ai.suggestion.dto.ApplySuggestionResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai/suggestions")
public class AiSuggestionController {

    private final AiSuggestionApplicationService suggestionApplicationService;
    private final AiSuggestionApplyService suggestionApplyService;

    public AiSuggestionController(
            AiSuggestionApplicationService suggestionApplicationService,
            AiSuggestionApplyService suggestionApplyService
    ) {
        this.suggestionApplicationService = suggestionApplicationService;
        this.suggestionApplyService = suggestionApplyService;
    }

    @GetMapping("/{suggestionId}")
    public AiSuggestionResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID suggestionId
    ) {
        return suggestionApplicationService.get(jwt, suggestionId);
    }

    @PostMapping("/{suggestionId}/dismiss")
    public AiSuggestionResponse dismiss(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID suggestionId
    ) {
        return suggestionApplicationService.dismiss(jwt, suggestionId);
    }

    @PostMapping("/{suggestionId}/apply")
    public ApplySuggestionResponse apply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID suggestionId,
            @Valid @org.springframework.web.bind.annotation.RequestBody
            ApplyIssueBreakdownRequest request
    ) {
        return suggestionApplyService.apply(jwt, suggestionId, request);
    }
}
