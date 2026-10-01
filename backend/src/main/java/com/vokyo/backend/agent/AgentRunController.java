package com.vokyo.backend.agent;

import com.vokyo.backend.agent.dto.AgentRunRequest;
import com.vokyo.backend.agent.dto.AgentRunResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a user starts a planning run, with their normal access token. The agent never
 * sees that token: it gets a separate one bound to this run's project.
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;

    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @PostMapping
    public AgentRunResponse start(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody AgentRunRequest request
    ) {
        return agentRunService.start(jwt, request);
    }
}
