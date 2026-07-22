package com.vokyo.backend.ai.suggestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.ai.AiMetrics;
import com.vokyo.backend.ai.breakdown.IssueBreakdownResult;
import com.vokyo.backend.ai.suggestion.dto.ApplyIssueBreakdownRequest;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

    public AiSuggestionApplyService(
            WorkspaceAccessService workspaceAccessService,
            AiSuggestionService suggestionService,
            ProjectAccessService projectAccessService,
            IssueRepository issueRepository,
            IssueCreationService issueCreationService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            AiMetrics metrics
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.suggestionService = suggestionService;
        this.projectAccessService = projectAccessService;
        this.issueRepository = issueRepository;
        this.issueCreationService = issueCreationService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
    }

    public ApplySuggestionResponse apply(
            Jwt jwt,
            UUID suggestionId,
            ApplyIssueBreakdownRequest request
    ) {
        ApplyOutcome outcome = transactionTemplate.execute(status ->
                applyInTransaction(jwt, suggestionId, request)
        );
        if (outcome == null) {
            throw new IllegalStateException("Apply transaction returned no result");
        }
        if (outcome.expired()) {
            if (metrics != null) {
                metrics.recordSuggestion(AiSuggestionType.ISSUE_BREAKDOWN, AiSuggestionStatus.EXPIRED);
                metrics.recordApply("expired", 0);
            }
            throw AiFeatureException.suggestionNotDraft();
        }
        if (metrics != null) {
            String result = outcome.replay() ? "idempotent_replay" : "success";
            metrics.recordApply(result, outcome.response().createdIssueIds().size());
            if (!outcome.replay()) {
                metrics.recordSuggestion(AiSuggestionType.ISSUE_BREAKDOWN, AiSuggestionStatus.APPLIED);
            }
        }
        return outcome.response();
    }

    private ApplyOutcome applyInTransaction(
            Jwt jwt,
            UUID suggestionId,
            ApplyIssueBreakdownRequest request
    ) {
        CurrentWorkspaceContext context =
                workspaceAccessService.requireCurrentContext(jwt);
        AiSuggestion suggestion = suggestionService.requireApplicableDraft(
                context,
                suggestionId,
                request.idempotencyKey()
        );

        if (suggestion.wasAppliedWith(request.idempotencyKey())) {
            return ApplyOutcome.replay(toResponse(suggestion));
        }
        if (suggestion.getStatus() == AiSuggestionStatus.EXPIRED) {
            return ApplyOutcome.expiredOutcome();
        }
        if (suggestion.getType() != AiSuggestionType.ISSUE_BREAKDOWN) {
            throw AiFeatureException.suggestionInvalid(
                    "Only issue breakdown suggestions can be applied"
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
            try {
                Issue created = issueCreationService.create(
                        context,
                        project,
                        toCreationCommand(selectedItem)
                );
                createdIssueIds.add(created.getId());
            } catch (ResponseStatusException exception) {
                throw AiFeatureException.suggestionInvalid(
                        applyValidationMessage(exception)
                );
            }
        }

        AiSuggestion applied = suggestionService.markApplied(
                suggestion,
                request.idempotencyKey(),
                createdIssueIds
        );
        return ApplyOutcome.success(toResponse(applied));
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
            List<ApplyIssueBreakdownRequest.Item> requestedItems
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
        for (ApplyIssueBreakdownRequest.Item requested : requestedItems) {
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
        ApplyIssueBreakdownRequest.Item request = selectedItem.request();
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
            ApplyIssueBreakdownRequest.Item request,
            IssueBreakdownResult.Item saved
    ) {
    }

    private record ApplyOutcome(
            ApplySuggestionResponse response,
            boolean expired,
            boolean replay
    ) {
        private static ApplyOutcome success(ApplySuggestionResponse response) {
            return new ApplyOutcome(response, false, false);
        }

        private static ApplyOutcome replay(ApplySuggestionResponse response) {
            return new ApplyOutcome(response, false, true);
        }

        private static ApplyOutcome expiredOutcome() {
            return new ApplyOutcome(null, true, false);
        }
    }
}
