package com.vokyo.backend.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.activity.dto.ActivityEventResponse;
import com.vokyo.backend.issue.dto.CreateCommentRequest;
import com.vokyo.backend.issue.dto.CreateIssueRequest;
import com.vokyo.backend.issue.dto.IssueCommentResponse;
import com.vokyo.backend.issue.dto.IssueDetailResponse;
import com.vokyo.backend.issue.dto.IssueSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @GetMapping
    public List<IssueSummaryResponse> listIssues(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID projectId,
            @RequestParam(required = false) IssueStatus status,
            @RequestParam(required = false) IssuePriority priority,
            @RequestParam(required = false) String q
    ) {
        return issueService.listIssues(jwt, projectId, status, priority, q);
    }

    @PostMapping
    public IssueSummaryResponse createIssue(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateIssueRequest request
    ) {
        return issueService.createIssue(jwt, request);
    }

    @GetMapping("/{issueId}")
    public IssueDetailResponse getIssue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID issueId
    ) {
        return issueService.getIssue(jwt, issueId);
    }

    @PostMapping("/{issueId}/comments")
    public IssueCommentResponse createComment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID issueId,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        return issueService.createComment(jwt, issueId, request);
    }

    @GetMapping("/{issueId}/activities")
    public List<ActivityEventResponse> listIssueActivities(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID issueId
    ) {
        return issueService.listIssueActivities(jwt, issueId);
    }

    @PatchMapping("/{issueId}")
    public IssueDetailResponse updateIssue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID issueId,
            @RequestBody JsonNode request
    ) {
        return issueService.updateIssue(jwt, issueId, request);
    }
}
