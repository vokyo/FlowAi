package com.vokyo.backend.ai.suggestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.breakdown.IssueBreakdownResult;
import com.vokyo.backend.ai.plan.ProjectPlan;
import com.vokyo.backend.ai.plan.ProjectPlanValidationException;
import com.vokyo.backend.ai.plan.ProjectPlanValidator;
import com.vokyo.backend.ai.suggestion.dto.ApplySuggestionRequest;
import com.vokyo.backend.ai.suggestion.dto.ApplySuggestionResponse;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueCreationCommand;
import com.vokyo.backend.issue.IssueCreationService;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectAccessService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AiSuggestionApplyService {

    private static final int MAX_DESCRIPTION_LENGTH = 10_000;

    private final WorkspaceAccessService workspaceAccessService;
    private final AiSuggestionService suggestionService;
    private final ProjectAccessService projectAccessService;
    private final IssueRepository issueRepository;
    private final IssueCreationService issueCreationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final AiMetrics metrics;
    private final ProjectPlanValidator projectPlanValidator;
    private final Clock clock;

    public AiSuggestionApplyService(
            WorkspaceAccessService workspaceAccessService,
            AiSuggestionService suggestionService,
            ProjectAccessService projectAccessService,
            IssueRepository issueRepository,
            IssueCreationService issueCreationService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            AiMetrics metrics,
            ProjectPlanValidator projectPlanValidator,
            Clock clock
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.suggestionService = suggestionService;
        this.projectAccessService = projectAccessService;
        this.issueRepository = issueRepository;
        this.issueCreationService = issueCreationService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.projectPlanValidator = projectPlanValidator;
        this.clock = clock;
    }

    public ApplySuggestionResponse apply(
            Jwt jwt,
            UUID suggestionId,
            ApplySuggestionRequest request
    ) {
        ApplyOutcome outcome = transactionTemplate.execute(status ->
                applyInTransaction(jwt, suggestionId, request)
        );
        if (outcome == null) {
            throw new IllegalStateException("Apply transaction returned no result");
        }
        if (outcome.expired()) {
            if (metrics != null) {
                metrics.recordSuggestion(outcome.type(), AiSuggestionStatus.EXPIRED);
                metrics.recordApply("expired", 0);
            }
            throw AiFeatureException.suggestionNotDraft();
        }
        if (metrics != null) {
            String result = outcome.replay() ? "idempotent_replay" : "success";
            metrics.recordApply(result, outcome.response().createdIssueIds().size());
            if (!outcome.replay()) {
                metrics.recordSuggestion(outcome.type(), AiSuggestionStatus.APPLIED);
            }
        }
        return outcome.response();
    }

    private ApplyOutcome applyInTransaction(
            Jwt jwt,
            UUID suggestionId,
            ApplySuggestionRequest request
    ) {
        CurrentWorkspaceContext context =
                workspaceAccessService.requireCurrentContext(jwt);
        AiSuggestion suggestion = suggestionService.requireApplicableDraft(
                context,
                suggestionId,
                request.idempotencyKey()
        );

        if (suggestion.wasAppliedWith(request.idempotencyKey())) {
            return ApplyOutcome.replay(suggestion.getType(), toResponse(suggestion));
        }
        if (suggestion.getStatus() == AiSuggestionStatus.EXPIRED) {
            return ApplyOutcome.expiredOutcome(suggestion.getType());
        }

        List<UUID> createdIssueIds = switch (suggestion.getType()) {
            case ISSUE_BREAKDOWN -> applyIssueBreakdown(context, suggestion, request);
            case PROJECT_PLAN -> applyProjectPlan(context, suggestion, request);
            case ISSUE_SUMMARY, PROJECT_SUMMARY -> throw AiFeatureException.suggestionInvalid(
                    "Only issue breakdown and project plan suggestions can be applied"
            );
        };

        AiSuggestion applied = suggestionService.markApplied(
                suggestion,
                request.idempotencyKey(),
                createdIssueIds
        );
        return ApplyOutcome.success(applied.getType(), toResponse(applied));
    }

    private List<UUID> applyIssueBreakdown(
            CurrentWorkspaceContext context,
            AiSuggestion suggestion,
            ApplySuggestionRequest request
    ) {
        if (request.items() == null || request.items().isEmpty()) {
            throw AiFeatureException.requestInvalid(
                    "Issue breakdown apply must include the suggestion's items"
            );
        }

        Project project = projectAccessService.requireAccessibleProjectForUpdate(
                suggestion.getProject().getId(),
                context
        );
        if (project.getArchivedAt() != null) {
            throw AiFeatureException.requestInvalid(
                    "Archived projects cannot apply issue breakdowns"
            );
        }
        Issue sourceIssue = requireActiveSourceIssue(suggestion, context, project);
        projectAccessService.requireIssueProjectAccess(sourceIssue, context);

        IssueBreakdownResult original = readContent(suggestion);
        List<SelectedItem> selectedItems = validateAndSelect(
                original,
                request.items()
        );

        List<UUID> createdIssueIds = new ArrayList<>();
        for (SelectedItem selectedItem : selectedItems) {
            createdIssueIds.add(createIssue(
                    context,
                    project,
                    toCreationCommand(selectedItem)
            ));
        }
        return createdIssueIds;
    }

    /**
     * A project plan is approved as a whole: every saved task becomes an issue, or
     * none does. The issues it names as existing are left alone. The plan is
     * re-validated here because assignees may have left the project, and the
     * existing issues it relies on may have been archived, since it was generated.
     */
    private List<UUID> applyProjectPlan(
            CurrentWorkspaceContext context,
            AiSuggestion suggestion,
            ApplySuggestionRequest request
    ) {
        if (request.items() != null && !request.items().isEmpty()) {
            throw AiFeatureException.requestInvalid(
                    "Project plans are applied as saved and take no items"
            );
        }

        Project project = projectAccessService.requireAccessibleProjectForUpdate(
                suggestion.getProject().getId(),
                context
        );
        if (project.getArchivedAt() != null) {
            throw AiFeatureException.requestInvalid(
                    "Archived projects cannot apply project plans"
            );
        }

        ProjectPlan plan = revalidateProjectPlan(suggestion, project);
        if (plan.items().isEmpty()) {
            throw AiFeatureException.requestInvalid(
                    "This plan only names existing issues and has no new tasks to create; dismiss it instead"
            );
        }
        List<UUID> createdIssueIds = new ArrayList<>();
        for (ProjectPlan.Item item : plan.items()) {
            createdIssueIds.add(createIssue(context, project, new IssueCreationCommand(
                    item.title(),
                    item.description(),
                    List.of(),
                    item.suggestedAssigneeUserId(),
                    null,
                    null,
                    item.priority(),
                    item.dueDate()
            )));
        }
        return createdIssueIds;
    }

    private ProjectPlan revalidateProjectPlan(AiSuggestion suggestion, Project project) {
        ProjectPlan savedPlan;
        try {
            savedPlan = objectMapper.treeToValue(
                    suggestion.getContent(),
                    ProjectPlan.class
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw AiFeatureException.suggestionInvalid(
                    "Saved project plan content is invalid"
            );
        }

        Set<UUID> activeMemberUserIds = projectAccessService.listActiveProjectMembers(project).stream()
                .map(member -> member.getUser().getId())
                .collect(Collectors.toUnmodifiableSet());
        // Membership is judged as of now, but dates from the day the plan was
        // generated: a due date passing while the plan waits for approval must not
        // make it impossible to approve.
        LocalDate generatedOn = LocalDate.ofInstant(suggestion.getCreatedAt(), clock.getZone());
        Set<UUID> activeIssueIds = savedPlan == null
                ? Set.of()
                : issueRepository.findActiveIdsInProject(
                        project.getWorkspace().getId(),
                        project.getId(),
                        savedPlan.referencedIssueIds()
                );
        try {
            return projectPlanValidator.validate(
                    savedPlan,
                    activeMemberUserIds,
                    activeIssueIds,
                    generatedOn
            );
        } catch (ProjectPlanValidationException exception) {
            throw AiFeatureException.suggestionInvalid(exception.getMessage());
        }
    }

    private UUID createIssue(
            CurrentWorkspaceContext context,
            Project project,
            IssueCreationCommand command
    ) {
        try {
            return issueCreationService.create(context, project, command).getId();
        } catch (ResponseStatusException exception) {
            throw AiFeatureException.suggestionInvalid(
                    applyValidationMessage(exception)
            );
        }
    }

    private Issue requireActiveSourceIssue(
            AiSuggestion suggestion,
            CurrentWorkspaceContext context,
            Project project
    ) {
        Issue sourceIssue = issueRepository.findByIdAndWorkspace_Id(
                        suggestion.getSourceIssue().getId(),
                        context.workspace().getId()
                )
                .filter(issue -> Objects.equals(
                        issue.getProject().getId(),
                        project.getId()
                ))
                .orElseThrow(AiFeatureException::suggestionNotFound);
        if (sourceIssue.getArchivedAt() != null) {
            throw AiFeatureException.requestInvalid(
                    "Archived issues cannot apply issue breakdowns"
            );
        }
        return sourceIssue;
    }

    private IssueBreakdownResult readContent(AiSuggestion suggestion) {
        try {
            return objectMapper.treeToValue(
                    suggestion.getContent(),
                    IssueBreakdownResult.class
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw AiFeatureException.suggestionInvalid(
                    "Saved issue breakdown content is invalid"
            );
        }
    }

    private List<SelectedItem> validateAndSelect(
            IssueBreakdownResult original,
            List<ApplySuggestionRequest.Item> requestedItems
    ) {
        if (original == null || original.items() == null
                || original.items().isEmpty()) {
            throw AiFeatureException.suggestionInvalid(
                    "Saved issue breakdown items are invalid"
            );
        }

        Map<String, IssueBreakdownResult.Item> originalById =
                new LinkedHashMap<>();
        for (IssueBreakdownResult.Item item : original.items()) {
            if (item == null || item.clientItemId() == null
                    || originalById.put(item.clientItemId(), item) != null) {
                throw AiFeatureException.suggestionInvalid(
                        "Saved issue breakdown item IDs are invalid"
                );
            }
        }

        Set<String> requestedIds = new LinkedHashSet<>();
        List<SelectedItem> selected = new ArrayList<>();
        for (ApplySuggestionRequest.Item requested : requestedItems) {
            String itemId = requested.clientItemId().trim();
            if (!requestedIds.add(itemId)) {
                throw AiFeatureException.suggestionInvalid(
                        "clientItemId values must be unique"
                );
            }
            IssueBreakdownResult.Item saved = originalById.get(itemId);
            if (saved == null) {
                throw AiFeatureException.suggestionInvalid(
                        "Apply items must match the saved suggestion"
                );
            }
            if (Boolean.TRUE.equals(requested.selected())) {
                selected.add(new SelectedItem(requested, saved));
            }
        }

        if (!requestedIds.equals(originalById.keySet())) {
            throw AiFeatureException.suggestionInvalid(
                    "Apply must include every saved suggestion item"
            );
        }
        if (selected.isEmpty()) {
            throw AiFeatureException.suggestionInvalid(
                    "At least one issue must be selected"
            );
        }
        return List.copyOf(selected);
    }

    private IssueCreationCommand toCreationCommand(SelectedItem selectedItem) {
        ApplySuggestionRequest.Item request = selectedItem.request();
        return new IssueCreationCommand(
                request.title(),
                appendAcceptanceCriteria(
                        request.description(),
                        selectedItem.saved().acceptanceCriteria()
                ),
                request.labelIds(),
                request.assigneeUserId(),
                request.workflowStateId(),
                null,
                request.priority(),
                request.dueDate()
        );
    }

    private String appendAcceptanceCriteria(
            String description,
            List<String> acceptanceCriteria
    ) {
        String base = description == null || description.isBlank()
                ? null
                : description.trim();
        List<String> criteria = acceptanceCriteria == null
                ? List.of()
                : acceptanceCriteria.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (criteria.isEmpty()) {
            return base;
        }

        String section = "Acceptance criteria:\n- "
                + String.join("\n- ", criteria);
        String merged = base == null ? section : base + "\n\n" + section;
        if (merged.length() > MAX_DESCRIPTION_LENGTH) {
            throw AiFeatureException.suggestionInvalid(
                    "Description and acceptance criteria exceed 10000 characters"
            );
        }
        return merged;
    }

    private String applyValidationMessage(ResponseStatusException exception) {
        String reason = exception.getReason();
        return reason == null || reason.isBlank()
                ? "An issue item is no longer valid"
                : reason;
    }

    private ApplySuggestionResponse toResponse(AiSuggestion suggestion) {
        return new ApplySuggestionResponse(
                suggestion.getId(),
                suggestion.getStatus(),
                suggestion.getCreatedIssueIds(),
                suggestion.getAppliedAt()
        );
    }

    private record SelectedItem(
            ApplySuggestionRequest.Item request,
            IssueBreakdownResult.Item saved
    ) {
    }

    private record ApplyOutcome(
            AiSuggestionType type,
            ApplySuggestionResponse response,
            boolean expired,
            boolean replay
    ) {
        private static ApplyOutcome success(AiSuggestionType type, ApplySuggestionResponse response) {
            return new ApplyOutcome(type, response, false, false);
        }

        private static ApplyOutcome replay(AiSuggestionType type, ApplySuggestionResponse response) {
            return new ApplyOutcome(type, response, false, true);
        }

        private static ApplyOutcome expiredOutcome(AiSuggestionType type) {
            return new ApplyOutcome(type, null, true, false);
        }
    }
}
