package com.vokyo.backend.ai.summary.issue;

import com.vokyo.backend.ai.summary.issue.dto.IssueSummaryRequest;
import com.vokyo.backend.ai.suggestion.dto.AiSuggestionResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai/issues")
public class IssueSummaryController {

    private final IssueSummaryService issueSummaryService;

    public IssueSummaryController(IssueSummaryService issueSummaryService) {
        this.issueSummaryService = issueSummaryService;
    }

    @PostMapping("/{issueId}/summary")
    public AiSuggestionResponse generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID issueId,
            @Valid @RequestBody IssueSummaryRequest request
    ) {
        return issueSummaryService.generate(jwt, issueId, request);
    }
}
