package com.vokyo.backend.agent;

import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tells the agent to drop a run's checkpoints once the run is over: approved,
 * cancelled, or ended without a plan to review. Called after the transaction that
 * ended the run has committed, so a rollback never leaves a run that can no longer
 * be revised. Best effort: checkpoints that stay behind only take space, so a
 * failure is logged and never fails the approval or cancellation.
 */
@Component
public class AgentCheckpoints {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckpoints.class);

    private final AgentTokenService agentTokenService;
    private final AgentServiceClient agentServiceClient;

    public AgentCheckpoints(AgentTokenService agentTokenService, AgentServiceClient agentServiceClient) {
        this.agentTokenService = agentTokenService;
        this.agentServiceClient = agentServiceClient;
    }

    public void discard(CurrentWorkspaceContext context, UUID projectId, UUID runId) {
        try {
            agentServiceClient.deleteRun(agentTokenService.issue(context, projectId, runId), runId);
        } catch (RuntimeException exception) {
            log.warn("event=agent_checkpoints_not_deleted runId={} reason={}", runId, exception.toString());
        }
    }
}
