package com.vokyo.backend.ai.breakdown;

import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownRequest;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownSuggestionResponse;
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
public class IssueBreakdownController {

    private final IssueBreakdownService breakdownService;

    public IssueBreakdownController(IssueBreakdownService breakdownService) {
        this.breakdownService = breakdownService;
    }

    @PostMapping("/{issueId}/breakdown")
    public IssueBreakdownSuggestionResponse generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID issueId,
            @Valid @RequestBody IssueBreakdownRequest request
    ) {
        return breakdownService.generate(jwt, issueId, request);
    }
}
