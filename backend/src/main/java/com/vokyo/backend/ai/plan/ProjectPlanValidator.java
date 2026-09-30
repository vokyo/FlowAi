package com.vokyo.backend.ai.plan;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic checks on a plan the agent produced. Everything in it came from the
 * model, so none of it is trusted: the same rules run before the plan is saved and
 * again when it is approved, since membership can change in between.
 */
@Component
public class ProjectPlanValidator {

    static final int MIN_ITEMS = 3;
    static final int MAX_ITEMS = 5;
    static final int MAX_OVERVIEW_LENGTH = 2_000;
    static final int MAX_CLIENT_ITEM_ID_LENGTH = 100;
    static final int MAX_TITLE_LENGTH = 240;
    static final int MAX_DESCRIPTION_LENGTH = 10_000;
    static final int MAX_DAYS_AHEAD = 365;

    /**
     * @param activeMemberUserIds the users who can be assigned: the project's active members right now
     * @param today the earliest day a due date may fall on
     * @return the same plan with its text trimmed
     * @throws ProjectPlanValidationException naming the first rule the plan breaks
     */
    public ProjectPlan validate(
            ProjectPlan plan,
            Set<UUID> activeMemberUserIds,
            LocalDate today
    ) {
        Objects.requireNonNull(activeMemberUserIds, "activeMemberUserIds is required");
        Objects.requireNonNull(today, "today is required");
        if (plan == null) {
            invalid("Plan is required");
        }

        String overview = requireText(plan.overview(), "overview");
        if (overview.length() > MAX_OVERVIEW_LENGTH) {
            invalid("overview exceeds " + MAX_OVERVIEW_LENGTH + " characters");
        }

        List<ProjectPlan.Item> items = plan.items();
        if (items == null || items.size() < MIN_ITEMS || items.size() > MAX_ITEMS) {
            invalid("Plan must contain between " + MIN_ITEMS + " and " + MAX_ITEMS + " items");
        }

        Set<String> clientItemIds = new HashSet<>();
        List<ProjectPlan.Item> normalizedItems = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            ProjectPlan.Item item = items.get(index);
            if (item == null) {
                invalid("Item at index " + index + " is missing");
            }
            normalizedItems.add(validateItem(item, index, clientItemIds, activeMemberUserIds, today));
        }

        return new ProjectPlan(overview, List.copyOf(normalizedItems));
    }

    private ProjectPlan.Item validateItem(
            ProjectPlan.Item item,
            int index,
            Set<String> clientItemIds,
            Set<UUID> activeMemberUserIds,
            LocalDate today
    ) {
        String clientItemId = requireText(item.clientItemId(), "clientItemId at item index " + index);
        if (clientItemId.length() > MAX_CLIENT_ITEM_ID_LENGTH) {
            invalid("clientItemId at item index " + index + " exceeds "
                    + MAX_CLIENT_ITEM_ID_LENGTH + " characters");
        }
        if (!clientItemIds.add(clientItemId)) {
            invalid("clientItemId values must be unique");
        }

        String title = requireText(item.title(), "title at item index " + index);
        if (title.length() > MAX_TITLE_LENGTH) {
            invalid("title at item index " + index + " exceeds " + MAX_TITLE_LENGTH + " characters");
        }

        String description = normalizeOptionalText(item.description());
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            invalid("description at item index " + index + " exceeds "
                    + MAX_DESCRIPTION_LENGTH + " characters");
        }

        if (item.priority() == null) {
            invalid("priority at item index " + index + " is required");
        }

        UUID assigneeUserId = item.suggestedAssigneeUserId();
        if (assigneeUserId != null && !activeMemberUserIds.contains(assigneeUserId)) {
            invalid("suggestedAssigneeUserId of item " + clientItemId
                    + " is not an active project member");
        }

        LocalDate dueDate = item.dueDate();
        if (dueDate != null && dueDate.isBefore(today)) {
            invalid("dueDate of item " + clientItemId + " is in the past");
        }
        if (dueDate != null && dueDate.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            invalid("dueDate of item " + clientItemId + " is more than "
                    + MAX_DAYS_AHEAD + " days away");
        }

        return new ProjectPlan.Item(
                clientItemId,
                title,
                description,
                item.priority(),
                assigneeUserId,
                dueDate
        );
    }

    private String requireText(String value, String field) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            invalid(field + " is required");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void invalid(String message) {
        throw new ProjectPlanValidationException(message);
    }
}
