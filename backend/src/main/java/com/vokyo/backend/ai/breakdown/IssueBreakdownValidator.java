package com.vokyo.backend.ai.breakdown;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class IssueBreakdownValidator {

    private static final int MIN_ITEMS = 2;
    private static final int MAX_TITLE_LENGTH = 240;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;

    public IssueBreakdownResult validate(
            IssueBreakdownResult result,
            IssueBreakdownContext context
    ) {
        Objects.requireNonNull(context, "context is required");
        requireValidItemCount(result, context);

        Set<UUID> allowedLabelIds = context.allowedCandidates().labels().stream()
                .map(IssueBreakdownContext.LabelCandidate::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> allowedAssigneeIds = context.allowedCandidates().members().stream()
                .map(IssueBreakdownContext.MemberCandidate::userId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        LinkedHashSet<String> warnings = normalizedTextSet(result.warnings());
        LinkedHashSet<String> clientItemIds = new LinkedHashSet<>();
        List<IssueBreakdownResult.Item> normalizedItems = new ArrayList<>();

        for (int index = 0; index < result.items().size(); index++) {
            IssueBreakdownResult.Item item = result.items().get(index);
            if (item == null) {
                invalid("Item at index " + index + " is missing");
            }

            String clientItemId = requireText(item.clientItemId(), "clientItemId", index);
            if (!clientItemIds.add(clientItemId)) {
                invalid("clientItemId values must be unique");
            }

            String title = requireText(item.title(), "title", index);
            if (title.length() > MAX_TITLE_LENGTH) {
                invalid("Title at item index " + index + " exceeds 240 characters");
            }

            String description = normalizeOptionalText(item.description());
            if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
                invalid("Description at item index " + index + " exceeds 10000 characters");
            }

            List<String> acceptanceCriteria = normalizedTextSet(item.acceptanceCriteria()).stream().toList();
            List<UUID> labelIds = normalizeLabels(
                    item.suggestedLabelIds(),
                    allowedLabelIds,
                    clientItemId,
                    warnings
            );
            UUID assigneeId = normalizeAssignee(
                    item.suggestedAssigneeUserId(),
                    allowedAssigneeIds,
                    clientItemId,
                    warnings
            );
            List<String> dependencies = normalizedTextSet(item.dependsOnClientItemIds()).stream().toList();

            normalizedItems.add(new IssueBreakdownResult.Item(
                    clientItemId,
                    title,
                    description,
                    item.priority(),
                    acceptanceCriteria,
                    labelIds,
                    assigneeId,
                    item.dueDate(),
                    dependencies
            ));
        }

        requireValidDependencies(normalizedItems, clientItemIds);

        return new IssueBreakdownResult(
                normalizeOptionalText(result.overview()),
                List.copyOf(normalizedItems),
                List.copyOf(warnings)
        );
    }

    private void requireValidItemCount(
            IssueBreakdownResult result,
            IssueBreakdownContext context
    ) {
        if (result == null || result.items() == null) {
            invalid("Breakdown items are required");
        }
        int count = result.items().size();
        if (count < MIN_ITEMS || count > context.maxItems()) {
            invalid("Breakdown must contain between 2 and " + context.maxItems() + " items");
        }
    }

    private List<UUID> normalizeLabels(
            List<UUID> suggestedLabelIds,
            Set<UUID> allowedLabelIds,
            String clientItemId,
            Set<String> warnings
    ) {
        if (suggestedLabelIds == null) {
            return List.of();
        }

        LinkedHashSet<UUID> retained = new LinkedHashSet<>();
        boolean removed = false;
        for (UUID labelId : suggestedLabelIds) {
            if (labelId != null && allowedLabelIds.contains(labelId)) {
                retained.add(labelId);
            } else {
                removed = true;
            }
        }
        if (removed) {
            warnings.add("Removed an unavailable label suggestion from " + clientItemId);
        }
        return List.copyOf(retained);
    }

    private UUID normalizeAssignee(
            UUID suggestedAssigneeUserId,
            Set<UUID> allowedAssigneeIds,
            String clientItemId,
            Set<String> warnings
    ) {
        if (suggestedAssigneeUserId == null || allowedAssigneeIds.contains(suggestedAssigneeUserId)) {
            return suggestedAssigneeUserId;
        }
        warnings.add("Removed an unavailable assignee suggestion from " + clientItemId);
        return null;
    }

    private void requireValidDependencies(
            List<IssueBreakdownResult.Item> items,
            Set<String> clientItemIds
    ) {
        Map<String, List<String>> graph = new HashMap<>();
        for (IssueBreakdownResult.Item item : items) {
            for (String dependency : item.dependsOnClientItemIds()) {
                if (!clientItemIds.contains(dependency)) {
                    invalid("Dependency references an unknown clientItemId");
                }
                if (item.clientItemId().equals(dependency)) {
                    invalid("An item cannot depend on itself");
                }
            }
            graph.put(item.clientItemId(), item.dependsOnClientItemIds());
        }

        Map<String, VisitState> states = new HashMap<>();
        for (String itemId : clientItemIds) {
            if (hasCycle(itemId, graph, states)) {
                invalid("Breakdown dependencies must not contain a cycle");
            }
        }
    }

    private boolean hasCycle(
            String itemId,
            Map<String, List<String>> graph,
            Map<String, VisitState> states
    ) {
        VisitState state = states.get(itemId);
        if (state == VisitState.VISITING) {
            return true;
        }
        if (state == VisitState.VISITED) {
            return false;
        }

        states.put(itemId, VisitState.VISITING);
        for (String dependency : graph.getOrDefault(itemId, List.of())) {
            if (hasCycle(dependency, graph, states)) {
                return true;
            }
        }
        states.put(itemId, VisitState.VISITED);
        return false;
    }

    private LinkedHashSet<String> normalizedTextSet(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String text = normalizeOptionalText(value);
            if (text != null) {
                normalized.add(text);
            }
        }
        return normalized;
    }

    private String requireText(String value, String field, int index) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            invalid(field + " at item index " + index + " is required");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void invalid(String message) {
        throw new IssueBreakdownValidationException(message);
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
