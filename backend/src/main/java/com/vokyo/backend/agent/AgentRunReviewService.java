package com.vokyo.backend.agent;

import com.vokyo.backend.agent.dto.AgentApprovalRequest;
import com.vokyo.backend.agent.dto.AgentApprovalResponse;
import com.vokyo.backend.agent.dto.AgentRevisionRequest;
import com.vokyo.backend.agent.dto.AgentRunDetailResponse;
import com.vokyo.backend.agent.dto.AgentRunResponse;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiGenerationRateLimiter;
import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.AiRateLimitExceededException;
import com.vokyo.backend.ai.plan.ProjectPlan;
import com.vokyo.backend.ai.plan.ProjectPlanValidationException;
import com.vokyo.backend.ai.suggestion.AiSuggestionApplyService;
import com.vokyo.backend.ai.suggestion.AiSuggestionApplyService.RunPlanApplication;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.ai.suggestion.dto.ApplySuggestionResponse;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import io.micrometer.core.instrument.Timer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * What the user who started a run does with it while its plan waits for review: read
 * it, revise the plan, approve a version, or cancel the run. The backend alone decides
 * these; the agent only writes plans and its token cannot write anything. Approving,
 * cancelling and saving a revision lock the run, so they happen one at a time and each
 * sees what the one before it did. Nothing is written to the project until a version
 * is approved. Once a run is approved or cancelled, the agent is told to drop its
 * checkpoints.
 */
@Service
public class AgentRunReviewService {

    private static final String REVISION_METRIC_FEATURE = "project_plan_revision";

    private final WorkspaceAccessService workspaceAccessService;
    private final ProjectAccessService projectAccessService;
    private final AgentRunRepository runRepository;
    private final AgentPlanVersions planVersions;
    private final AiSuggestionApplyService applyService;
    private final AiGenerationRateLimiter rateLimiter;
    private final AgentTokenService agentTokenService;
    private final AgentServiceClient agentServiceClient;
    private final AgentCheckpoints checkpoints;
    private final AgentRunLock runLock;
    private final AiMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    public AgentRunReviewService(
        WorkspaceAccessService workspaceAccessService,
        ProjectAccessService projectAccessService,
        AgentRunRepository runRepository,
        AgentPlanVersions planVersions,
        AiSuggestionApplyService applyService,
        AiGenerationRateLimiter rateLimiter,
        AgentTokenService agentTokenService,
        AgentServiceClient agentServiceClient,
        AgentCheckpoints checkpoints,
        AgentRunLock runLock,
        AiMetrics metrics,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.projectAccessService = projectAccessService;
        this.runRepository = runRepository;
        this.planVersions = planVersions;
        this.applyService = applyService;
        this.rateLimiter = rateLimiter;
        this.agentTokenService = agentTokenService;
        this.agentServiceClient = agentServiceClient;
        this.checkpoints = checkpoints;
        this.runLock = runLock;
        this.metrics = metrics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public AgentRunDetailResponse get(Jwt jwt, UUID runId) {
        return Objects.requireNonNull(readOnlyTransaction.execute(status -> {
            CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
            return detail(requireRun(runId, context, false));
        }));
    }

    /**
     * Asks the agent to revise the latest version. Like starting a run, the call to the
     * agent holds no transaction: the checks before it and the save after it each take
     * a short one. The save checks again, under the run's lock, that nothing replaced
     * the version or closed the run while the agent was working; if something did, the
     * revision is discarded. A revision that ends without a plan leaves the run as it was.
     */
    public AgentRunResponse revise(Jwt jwt, UUID runId, AgentRevisionRequest request) {
        Timer.Sample timer = metrics.start();
        String metricResult = "internal_error";
        try {
            RevisionScope scope = Objects.requireNonNull(readOnlyTransaction.execute(
                status -> requireRevisionScope(jwt, runId, request.basedOnVersion())
            ));
            rateLimiter.requirePermit(scope.context());

            AgentRunResponse response = runLock.whileHeld(
                scope.context().user().getId(),
                scope.project().getId(),
                () -> reviseWithTheAgent(scope, runId, request)
            );
            metricResult = AgentRunService.metricResult(response);
            return response;
        } catch (AiRateLimitExceededException exception) {
            metricResult = "rate_limited";
            throw exception;
        } catch (AiFeatureException exception) {
            metricResult = AgentRunService.metricResult(exception);
            throw exception;
        } catch (ResponseStatusException exception) {
            metricResult = "request_rejected";
            throw exception;
        } finally {
            metrics.complete(timer, REVISION_METRIC_FEATURE, metricResult, null, null);
        }
    }

    private AgentRunResponse reviseWithTheAgent(RevisionScope scope, UUID runId, AgentRevisionRequest request) {
        String feedback = request.feedback().strip();
        AgentRunResult result = agentServiceClient.resume(
            agentTokenService.issue(scope.context(), scope.project().getId(), runId),
            runId,
            scope.checkpointId(),
            feedback
        );
        AgentRunService.logRun(runId, scope.project(), result);

        return switch (result.status()) {
            case PLANNED -> saveRevision(
                scope.context(), runId, request.basedOnVersion(), AgentRunService.requirePlan(result),
                result, feedback
            );
            case INSUFFICIENT_INFO -> AgentRunService.insufficient(runId, result);
            case FAILED -> throw AiFeatureException.agentRunFailed();
        };
    }

    /**
     * Approves one version: it must be the latest, carry the hash the user was shown,
     * and still pass validation against the project as it is now. Its tasks become
     * issues in the same transaction that marks the run approved. Approving the same
     * version again returns the first approval's issues. A version found unapprovable
     * here is marked so, with the reason, and the run stays open to be revised.
     */
    public AgentApprovalResponse approve(Jwt jwt, UUID runId, AgentApprovalRequest request) {
        Approval approval = Objects.requireNonNull(transaction.execute(
            status -> approveInTransaction(jwt, runId, request)
        ));
        if (approval.notApprovableReason() != null) {
            metrics.recordApply("not_approvable", 0);
            throw AiFeatureException.planVersionNotApprovable(approval.notApprovableReason());
        }
        if (approval.expired()) {
            metrics.recordSuggestion(AiSuggestionType.PROJECT_PLAN, AiSuggestionStatus.EXPIRED);
            metrics.recordApply("expired", 0);
            throw AiFeatureException.suggestionNotDraft();
        }
        ApplySuggestionResponse applied = approval.applied();
        metrics.recordApply(approval.replay() ? "idempotent_replay" : "success", applied.createdIssueIds().size());
        if (!approval.replay()) {
            metrics.recordSuggestion(AiSuggestionType.PROJECT_PLAN, AiSuggestionStatus.APPLIED);
            checkpoints.discard(approval.context(), approval.projectId(), runId);
        }
        return new AgentApprovalResponse(runId, request.version(), applied.createdIssueIds(), applied.appliedAt());
    }

    /** Ends a run under review without writing anything to the project. Cancelling it again is harmless. */
    public AgentRunDetailResponse cancel(Jwt jwt, UUID runId) {
        Cancellation cancellation = Objects.requireNonNull(transaction.execute(status -> {
            CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
            AgentRun run = requireRun(runId, context, true);
            CurrentWorkspaceContext cancelledBy = null;
            switch (run.getState()) {
                case REVIEWING -> {
                    planVersions.retireDraft(planVersions.requireVersion(run, run.getLatestVersion()));
                    run.cancel(clock.instant());
                    cancelledBy = context;
                }
                case CANCELLED -> {
                }
                case APPROVED -> throw AiFeatureException.agentRunClosed("An approved run cannot be cancelled");
            }
            return new Cancellation(detail(run), cancelledBy);
        }));
        if (cancellation.cancelledBy() != null) {
            checkpoints.discard(cancellation.cancelledBy(), cancellation.run().projectId(), runId);
        }
        return cancellation.run();
    }

    private RevisionScope requireRevisionScope(Jwt jwt, UUID runId, int basedOnVersion) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        AgentRun run = requireRun(runId, context, false);
        requireOpenForRevision(run, basedOnVersion);
        Project project = requireActiveProject(run, context);
        String checkpointId = planVersions.requireVersion(run, run.getLatestVersion()).getCheckpointId();
        return new RevisionScope(context, project, checkpointId);
    }

    private AgentRunResponse saveRevision(
        CurrentWorkspaceContext context,
        UUID runId,
        int basedOnVersion,
        ProjectPlan plan,
        AgentRunResult result,
        String feedback
    ) {
        return Objects.requireNonNull(transaction.execute(status -> {
            AgentRun run = requireRun(runId, context, true);
            requireOpenForRevision(run, basedOnVersion);
            Project project = requireActiveProject(run, context);
            AgentPlanVersion version = planVersions.addRevision(
                context, project, run, plan, result.checkpointId(), feedback
            );
            return planVersions.plannedResponse(version, result.stats());
        }));
    }

    private Approval approveInTransaction(Jwt jwt, UUID runId, AgentApprovalRequest request) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        AgentRun run = requireRun(runId, context, true);
        int requested = request.version();
        if (run.getState() == AgentRunState.CANCELLED) {
            throw AiFeatureException.agentRunClosed("This run was cancelled");
        }
        if (run.getState() == AgentRunState.APPROVED && requested != run.getLatestVersion()) {
            throw AiFeatureException.agentRunClosed(
                "This run was approved with version " + run.getLatestVersion());
        }
        requireLatest(run, requested);

        AgentPlanVersion version = planVersions.requireVersion(run, requested);
        if (!version.getContentHash().equals(request.contentHash())) {
            throw AiFeatureException.planVersionChanged();
        }
        if (!version.isApprovable()) {
            throw AiFeatureException.planVersionNotApprovable(version.getRejectionReason());
        }

        UUID idempotencyKey = idempotencyKey(run, requested);
        UUID projectId = run.getProject().getId();
        if (run.getState() == AgentRunState.APPROVED) {
            return Approval.of(applyService.applyRunPlan(
                context, version.getSuggestion().getId(), idempotencyKey, run.getGeneratedOn()), context, projectId);
        }

        Project project = projectAccessService.requireAccessibleProjectForUpdate(projectId, context);
        try {
            planVersions.validate(project, planVersions.readPlan(version), run.getGeneratedOn());
        } catch (ProjectPlanValidationException exception) {
            // Committed, not rolled back: the version stays marked for whoever looks next.
            version.markNotApprovable(exception.getMessage());
            planVersions.retireDraft(version);
            return Approval.notApprovable(version.getRejectionReason());
        }

        RunPlanApplication application = applyService.applyRunPlan(
            context, version.getSuggestion().getId(), idempotencyKey, run.getGeneratedOn());
        if (!application.expired() && !application.replay()) {
            run.approve(clock.instant());
        }
        return Approval.of(application, context, projectId);
    }

    private AgentRun requireRun(UUID runId, CurrentWorkspaceContext context, boolean forUpdate) {
        UUID workspaceId = context.workspace().getId();
        UUID userId = context.user().getId();
        return (forUpdate
            ? runRepository.findOwnedForUpdate(runId, workspaceId, userId)
            : runRepository.findOwned(runId, workspaceId, userId))
            .orElseThrow(AiFeatureException::agentRunNotFound);
    }

    private static void requireOpenForRevision(AgentRun run, int basedOnVersion) {
        switch (run.getState()) {
            case APPROVED -> throw AiFeatureException.agentRunClosed("This run was already approved");
            case CANCELLED -> throw AiFeatureException.agentRunClosed("This run was cancelled");
            case REVIEWING -> {
            }
        }
        requireLatest(run, basedOnVersion);
        if (!run.hasRoomForAnotherVersion()) {
            throw AiFeatureException.planVersionLimitReached(AgentRun.MAX_VERSIONS);
        }
    }

    private static void requireLatest(AgentRun run, int version) {
        if (version > run.getLatestVersion()) {
            throw AiFeatureException.requestInvalid("This run has no version " + version);
        }
        if (version != run.getLatestVersion()) {
            throw AiFeatureException.planVersionOutdated(version, run.getLatestVersion());
        }
    }

    private Project requireActiveProject(AgentRun run, CurrentWorkspaceContext context) {
        Project project = projectAccessService.requireAccessibleProject(run.getProject().getId(), context);
        if (project.getArchivedAt() != null) {
            throw AiFeatureException.requestInvalid("Archived projects cannot revise planning runs");
        }
        return project;
    }

    private AgentRunDetailResponse detail(AgentRun run) {
        return new AgentRunDetailResponse(
            run.getId(),
            run.getProject().getId(),
            run.getGoal(),
            run.getGeneratedOn(),
            run.getState(),
            run.getLatestVersion(),
            planVersions.versionsOf(run).stream()
                .map(version -> new AgentRunDetailResponse.Version(
                    version.getVersion(),
                    version.isApprovable(),
                    version.getRejectionReason(),
                    version.getContentHash(),
                    planVersions.readPlan(version),
                    version.getCreatedAt()
                ))
                .toList(),
            run.getCreatedAt(),
            run.getUpdatedAt()
        );
    }

    /**
     * Derived from the run and version, so approving the same version twice, from two
     * tabs or a retried request, applies it once and replays the result.
     */
    static UUID idempotencyKey(AgentRun run, int version) {
        return UUID.nameUUIDFromBytes(
            ("agent-run:" + run.getId() + ":version:" + version).getBytes(StandardCharsets.UTF_8));
    }

    private record RevisionScope(CurrentWorkspaceContext context, Project project, String checkpointId) {
    }

    private record Approval(
        ApplySuggestionResponse applied,
        boolean expired,
        boolean replay,
        String notApprovableReason,
        CurrentWorkspaceContext context,
        UUID projectId
    ) {

        static Approval of(RunPlanApplication application, CurrentWorkspaceContext context, UUID projectId) {
            return new Approval(
                application.response(), application.expired(), application.replay(), null, context, projectId);
        }

        static Approval notApprovable(String reason) {
            return new Approval(null, false, false, reason, null, null);
        }
    }

    /** cancelledBy is set only when this call cancelled the run, not when it already was. */
    private record Cancellation(AgentRunDetailResponse run, CurrentWorkspaceContext cancelledBy) {
    }
}
