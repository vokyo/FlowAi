package com.vokyo.backend.agent;

import com.vokyo.backend.agent.dto.AgentRunRequest;
import com.vokyo.backend.agent.dto.AgentRunResponse;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGenerationRateLimiter;
import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.AiRateLimitExceededException;
import com.vokyo.backend.ai.plan.ProjectPlan;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Starts a planning run for the current user and saves its plan as the run's first
 * version, to be reviewed. Nothing the agent returns is trusted: the plan goes through
 * the same validator that runs again when it is approved, and a plan that fails it is
 * kept as a version that cannot be approved, with the reason, so it can be revised.
 *
 * <p>Deliberately not transactional as a whole: the access checks run in one short
 * read-only transaction, and the call to the agent, which can take up to the read
 * timeout, runs outside any transaction so no database connection is held while it
 * waits. The agent only reads, so a run that fails or times out leaves nothing behind.
 */
@Service
public class AgentRunService {

    static final int MAX_MISSING_ITEMS = 5;
    static final int MAX_MISSING_LENGTH = 500;
    private static final String METRIC_FEATURE = "project_plan";
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;
    private final AiGenerationRateLimiter rateLimiter;
    private final AgentTokenService agentTokenService;
    private final AgentServiceClient agentServiceClient;
    private final AgentPlanVersions planVersions;
    private final AgentCheckpoints checkpoints;
    private final AiMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate readOnlyTransaction;

    public AgentRunService(
        WorkspaceAccessService workspaceAccessService,
        ProjectAccessService projectAccessService,
        AiGenerationRateLimiter rateLimiter,
        AgentTokenService agentTokenService,
        AgentServiceClient agentServiceClient,
        AgentPlanVersions planVersions,
        AgentCheckpoints checkpoints,
        AiMetrics metrics,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
        this.rateLimiter = rateLimiter;
        this.agentTokenService = agentTokenService;
        this.agentServiceClient = agentServiceClient;
        this.planVersions = planVersions;
        this.checkpoints = checkpoints;
        this.metrics = metrics;
        this.clock = clock;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public AgentRunResponse start(Jwt jwt, AgentRunRequest request) {
        Timer.Sample timer = metrics.start();
        String metricResult = "internal_error";
        try {
            RunScope scope = Objects.requireNonNull(readOnlyTransaction.execute(
                status -> requireRunScope(jwt, request.projectId())
            ));
            CurrentWorkspaceContext context = scope.context();
            Project project = scope.project();
            rateLimiter.requirePermit(context);

            UUID runId = UUID.randomUUID();
            // One UTC date for the whole run: the model schedules from it and the plan's
            // due dates are validated against it.
            LocalDate today = LocalDate.now(clock);
            String goal = request.goal().strip();
            AgentRunResult result = agentServiceClient.run(
                agentTokenService.issue(context, project.getId(), runId),
                runId,
                goal,
                today
            );
            logRun(runId, project, result);

            AgentRunResponse response;
            try {
                response = switch (result.status()) {
                    case PLANNED -> planVersions.plannedResponse(
                        planVersions.startRun(context, project, runId, goal, today, requirePlan(result),
                            result.checkpointId()),
                        result.stats()
                    );
                    case INSUFFICIENT_INFO -> insufficient(runId, result);
                    case FAILED -> throw AiFeatureException.agentRunFailed();
                };
            } catch (RuntimeException exception) {
                // The agent finished, but no run was saved for anyone to revise.
                checkpoints.discard(context, project.getId(), runId);
                throw exception;
            }
            if (response.status() != AgentRunStatus.PLANNED) {
                checkpoints.discard(context, project.getId(), runId);
            }
            metricResult = metricResult(response);
            return response;
        } catch (AiRateLimitExceededException exception) {
            metricResult = "rate_limited";
            throw exception;
        } catch (AiFeatureException exception) {
            metricResult = metricResult(exception);
            throw exception;
        } catch (ResponseStatusException exception) {
            metricResult = "request_rejected";
            throw exception;
        } finally {
            metrics.complete(timer, METRIC_FEATURE, metricResult, null, null);
        }
    }

    /**
     * Who is asking and for which project, loaded together so the project is fully
     * initialized before the transaction ends: everything after this runs outside one.
     */
    private RunScope requireRunScope(Jwt jwt, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Project project = projectAccessService.requireAccessibleProject(projectId, context);
        if (project.getArchivedAt() != null) {
            throw AiFeatureException.requestInvalid("Archived projects cannot start planning runs");
        }
        return new RunScope(context, project);
    }

    static ProjectPlan requirePlan(AgentRunResult result) {
        if (result.plan() == null) {
            throw AiFeatureException.agentInvalidResponse("Planning agent returned PLANNED without a plan");
        }
        return result.plan();
    }

    static AgentRunResponse insufficient(UUID runId, AgentRunResult result) {
        return new AgentRunResponse(
            runId,
            AgentRunStatus.INSUFFICIENT_INFO,
            null,
            null,
            null,
            null,
            null,
            requireMissing(result.missing()),
            result.stats()
        );
    }

    static String metricResult(AgentRunResponse response) {
        if (response.status() == AgentRunStatus.INSUFFICIENT_INFO) {
            return "insufficient_info";
        }
        return Boolean.TRUE.equals(response.approvable()) ? "success" : "not_approvable";
    }

    static List<String> requireMissing(List<String> missing) {
        boolean valid = missing != null
            && !missing.isEmpty()
            && missing.size() <= MAX_MISSING_ITEMS
            && missing.stream().allMatch(item -> item != null
                && !item.isBlank()
                && item.strip().length() <= MAX_MISSING_LENGTH);
        if (!valid) {
            throw AiFeatureException.agentInvalidResponse(
                "Planning agent must say what is missing in 1 to " + MAX_MISSING_ITEMS
                    + " short sentences of at most " + MAX_MISSING_LENGTH + " characters"
            );
        }
        return missing.stream().map(String::strip).toList();
    }

    static void logRun(UUID runId, Project project, AgentRunResult result) {
        AgentRunResult.Stats stats = result.stats();
        log.info(
            "event=agent_run runId={} projectId={} status={} decisionRounds={} toolCalls={}",
            runId,
            project.getId(),
            result.status(),
            stats == null ? null : stats.decisionRounds(),
            stats == null ? null : stats.toolCalls()
        );
        if (result.status() == AgentRunStatus.FAILED) {
            log.warn("event=agent_run_failed runId={} reason={}", runId, abbreviate(result.reason()));
        }
    }

    private static String abbreviate(String reason) {
        if (reason == null) {
            return null;
        }
        String stripped = reason.strip();
        return stripped.length() <= 300 ? stripped : stripped.substring(0, 300) + "…";
    }

    private record RunScope(CurrentWorkspaceContext context, Project project) {
    }

    static String metricResult(AiFeatureException exception) {
        return switch (exception.code()) {
            case "AI_AGENT_UNAVAILABLE" -> "provider_unavailable";
            case "AI_AGENT_TIMEOUT" -> "timeout";
            case "AI_AGENT_INVALID_RESPONSE" -> "invalid_response";
            case "AI_AGENT_RUN_FAILED" -> "agent_failed";
            case "AI_REQUEST_INVALID", "AI_AGENT_RUN_NOT_FOUND", "AI_AGENT_RUN_CLOSED",
                 "AI_PLAN_VERSION_OUTDATED", "AI_PLAN_VERSION_LIMIT" -> "request_rejected";
            default -> "failed";
        };
    }
}
