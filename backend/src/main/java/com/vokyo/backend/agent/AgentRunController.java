package com.vokyo.backend.agent;

import com.vokyo.backend.agent.dto.AgentApprovalRequest;
import com.vokyo.backend.agent.dto.AgentApprovalResponse;
import com.vokyo.backend.agent.dto.AgentRevisionRequest;
import com.vokyo.backend.agent.dto.AgentRunDetailResponse;
import com.vokyo.backend.agent.dto.AgentRunRequest;
import com.vokyo.backend.agent.dto.AgentRunResponse;
import com.vokyo.backend.agent.dto.AgentRunSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Where a user starts a planning run and reviews its plan, with their normal access
 * token. The agent never sees that token: it gets a separate one bound to the run's
 * project, issued again for every revision.
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final AgentRunReviewService reviewService;

    public AgentRunController(AgentRunService agentRunService, AgentRunReviewService reviewService) {
        this.agentRunService = agentRunService;
        this.reviewService = reviewService;
    }

    @PostMapping
    public AgentRunResponse start(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody AgentRunRequest request
    ) {
        return agentRunService.start(jwt, request);
    }

    @GetMapping
    public List<AgentRunSummaryResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID projectId) {
        return reviewService.list(jwt, projectId);
    }

    @GetMapping("/{runId}")
    public AgentRunDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        return reviewService.get(jwt, runId);
    }

    @PostMapping("/{runId}/revisions")
    public AgentRunResponse revise(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID runId,
        @Valid @RequestBody AgentRevisionRequest request
    ) {
        return reviewService.revise(jwt, runId, request);
    }

    @PostMapping("/{runId}/approve")
    public AgentApprovalResponse approve(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID runId,
        @Valid @RequestBody AgentApprovalRequest request
    ) {
        return reviewService.approve(jwt, runId, request);
    }

    @PostMapping("/{runId}/cancel")
    public AgentRunDetailResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        return reviewService.cancel(jwt, runId);
    }
}
