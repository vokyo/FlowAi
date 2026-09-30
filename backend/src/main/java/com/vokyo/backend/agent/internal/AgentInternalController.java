package com.vokyo.backend.agent.internal;

import com.vokyo.backend.agent.internal.dto.AgentIssueSearchResponse;
import com.vokyo.backend.agent.internal.dto.AgentProjectMembersResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only tools the planning agent calls back into. There is deliberately no
 * projectId parameter: the project is the one bound into the agent token.
 */
@RestController
@RequestMapping("/api/internal/agent/project")
public class AgentInternalController {

    private final AgentProjectQueryService queryService;

    public AgentInternalController(AgentProjectQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/issues")
    public AgentIssueSearchResponse searchIssues(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "" + AgentProjectQueryService.MAX_ISSUE_RESULTS) int limit
    ) {
        return queryService.searchIssues(jwt, q, limit);
    }

    @GetMapping("/members")
    public AgentProjectMembersResponse listMembers(@AuthenticationPrincipal Jwt jwt) {
        return queryService.listMembers(jwt);
    }
}
