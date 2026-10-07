package com.vokyo.backend.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.agent.dto.AgentRunResponse;
import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.plan.ProjectPlan;
import com.vokyo.backend.ai.plan.ProjectPlanValidationException;
import com.vokyo.backend.ai.plan.ProjectPlanValidator;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionService;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Saves the plans the agent returns as versions of their run. A plan is validated as
 * approving it will validate it again. One that passes gets a draft it is approved
 * through; one that does not is kept with the reason, so the user sees what was wrong
 * and can revise it instead of losing the run.
 */
@Service
public class AgentPlanVersions {

    static final String PROMPT_VERSION = "planning-agent-v1";
    static final String NOTHING_TO_CREATE =
        "This version only reuses existing issues and has no new task to create";

    private final AgentRunRepository runRepository;
    private final AgentPlanVersionRepository versionRepository;
    private final ProjectAccessService projectAccessService;
    private final IssueRepository issueRepository;
    private final ProjectPlanValidator projectPlanValidator;
    private final AiSuggestionService suggestionService;
    private final AiMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentPlanVersions(
        AgentRunRepository runRepository,
        AgentPlanVersionRepository versionRepository,
        ProjectAccessService projectAccessService,
        IssueRepository issueRepository,
        ProjectPlanValidator projectPlanValidator,
        AiSuggestionService suggestionService,
        AiMetrics metrics,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.runRepository = runRepository;
        this.versionRepository = versionRepository;
        this.projectAccessService = projectAccessService;
        this.issueRepository = issueRepository;
        this.projectPlanValidator = projectPlanValidator;
        this.suggestionService = suggestionService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Saves a new run under review with the agent's first plan as version 1. */
    @Transactional
    public AgentPlanVersion startRun(
        CurrentWorkspaceContext context,
        Project project,
        UUID runId,
        String goal,
        LocalDate generatedOn,
        ProjectPlan plan,
        String checkpointId
    ) {
        AgentRun run = runRepository.save(new AgentRun(
            runId, context.workspace(), project, context.user(), goal, generatedOn, clock.instant()
        ));
        return saveVersion(context, project, run, 1, plan, checkpointId, null);
    }

    /**
     * Saves a revised plan as the run's next version. The version it replaces can no
     * longer be approved. Joins the caller's transaction, which holds the run's lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentPlanVersion addRevision(
        CurrentWorkspaceContext context,
        Project project,
        AgentRun run,
        ProjectPlan plan,
        String checkpointId,
        String feedback
    ) {
        retireDraft(requireVersion(run, run.getLatestVersion()));
        int version = run.addVersion(clock.instant());
        return saveVersion(context, project, run, version, plan, checkpointId, feedback);
    }

    public AgentPlanVersion requireVersion(AgentRun run, int version) {
        return versionRepository.findByRun_IdAndVersion(run.getId(), version)
            .orElseThrow(() -> new IllegalStateException(
                "Run " + run.getId() + " has no version " + version));
    }

    public List<AgentPlanVersion> versionsOf(AgentRun run) {
        return versionRepository.findByRun_IdOrderByVersion(run.getId());
    }

    /** Dismisses a version's draft, if it still has one, so it can no longer be applied. */
    public void retireDraft(AgentPlanVersion version) {
        AiSuggestion suggestion = version.getSuggestion();
        if (suggestion != null && suggestion.getStatus() == AiSuggestionStatus.DRAFT) {
            suggestion.dismiss(clock.instant());
            metrics.recordSuggestion(AiSuggestionType.PROJECT_PLAN, AiSuggestionStatus.DISMISSED);
        }
    }

    /**
     * Validates a plan against the project as it is now: assignees must still be active
     * members and reused issues still active. Dates are judged from the run's date.
     */
    public ProjectPlan validate(Project project, ProjectPlan plan, LocalDate generatedOn) {
        Set<UUID> activeMemberUserIds = projectAccessService.listActiveProjectMembers(project).stream()
            .map(member -> member.getUser().getId())
            .collect(Collectors.toUnmodifiableSet());
        Set<UUID> activeIssueIds = issueRepository.findActiveIdsInProject(
            project.getWorkspace().getId(),
            project.getId(),
            plan.referencedIssueIds()
        );
        return projectPlanValidator.validate(plan, activeMemberUserIds, activeIssueIds, generatedOn);
    }

    public AgentRunResponse plannedResponse(AgentPlanVersion version, AgentRunResult.Stats stats) {
        return new AgentRunResponse(
            version.getRun().getId(),
            AgentRunStatus.PLANNED,
            version.getVersion(),
            version.isApprovable(),
            version.getRejectionReason(),
            version.getContentHash(),
            readPlan(version),
            List.of(),
            stats
        );
    }

    public ProjectPlan readPlan(AgentPlanVersion version) {
        try {
            return objectMapper.treeToValue(version.getContent(), ProjectPlan.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Saved plan of version " + version.getVersion() + " cannot be read",
                exception);
        }
    }

    private AgentPlanVersion saveVersion(
        CurrentWorkspaceContext context,
        Project project,
        AgentRun run,
        int version,
        ProjectPlan plan,
        String checkpointId,
        String feedback
    ) {
        Instant now = clock.instant();
        ProjectPlan validated;
        try {
            validated = validate(project, plan, run.getGeneratedOn());
        } catch (ProjectPlanValidationException exception) {
            return versionRepository.save(AgentPlanVersion.notApprovable(
                run, version, objectMapper.valueToTree(plan), exception.getMessage(), checkpointId, now
            ));
        }

        JsonNode content = objectMapper.valueToTree(validated);
        if (validated.items().isEmpty()) {
            return versionRepository.save(AgentPlanVersion.notApprovable(
                run, version, content, NOTHING_TO_CREATE, checkpointId, now
            ));
        }
        AiSuggestion suggestion = suggestionService.createDraft(new AiSuggestionService.CreateDraftCommand(
            context,
            project,
            null,
            AiSuggestionType.PROJECT_PLAN,
            content,
            PROMPT_VERSION,
            null,
            null,
            canonicalInput(run, version, feedback),
            null,
            null
        ));
        metrics.recordSuggestion(AiSuggestionType.PROJECT_PLAN, suggestion.getStatus());
        return versionRepository.save(AgentPlanVersion.approvable(
            run, version, content, suggestion, checkpointId, now
        ));
    }

    /**
     * What a version was asked, for its draft's input hash: the run's project, date and
     * goal, and for a revision the feedback it was given.
     */
    private static String canonicalInput(AgentRun run, int version, String feedback) {
        String input = "project=" + run.getProject().getId()
            + "\ntoday=" + run.getGeneratedOn()
            + "\ngoal=" + run.getGoal()
            + "\nversion=" + version;
        return feedback == null ? input : input + "\nfeedback=" + feedback;
    }
}
