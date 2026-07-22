package com.vokyo.backend.ai.summary.project;

import com.vokyo.backend.ai.summary.project.dto.ProjectSummaryRequest;
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
@RequestMapping("/api/ai/projects")
public class ProjectSummaryController {
    private final ProjectSummaryService projectSummaryService;

    public ProjectSummaryController(ProjectSummaryService projectSummaryService) {
        this.projectSummaryService = projectSummaryService;
    }

    @PostMapping("/{projectId}/summary")
    public AiSuggestionResponse generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectSummaryRequest request
    ) {
        return projectSummaryService.generate(jwt, projectId, request);
    }
}
