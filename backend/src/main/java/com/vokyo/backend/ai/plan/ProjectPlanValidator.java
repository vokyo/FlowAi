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

    static final int MAX_ITEMS = 5;
    static final int MAX_EXISTING_ISSUES = 10;
    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_OVERVIEW_LENGTH = 2_000;
    static final int MAX_CLIENT_ITEM_ID_LENGTH = 100;
    static final int MAX_TITLE_LENGTH = 240;
    static final int MAX_DESCRIPTION_LENGTH = 10_000;
    static final int MAX_DAYS_AHEAD = 365;

    /**
     * @param activeMemberUserIds the users who can be assigned: the project's active members right now
     * @param activeIssueIds which of the issues the plan names as existing are active issues of
     *                       this project right now; any other id the plan names is rejected
     * @param referenceDate the day due dates are judged from: today when the plan is
     *                      generated, and the day it was generated when it is approved
     * @return the same plan with its text trimmed
     * @throws ProjectPlanValidationException naming the first rule the plan breaks
     */
    public ProjectPlan validate(
            ProjectPlan plan,
            Set<UUID> activeMemberUserIds,
            Set<UUID> activeIssueIds,
            LocalDate referenceDate
    ) {
        Objects.requireNonNull(activeMemberUserIds, "activeMemberUserIds is required");
        Objects.requireNonNull(activeIssueIds, "activeIssueIds is required");
        Objects.requireNonNull(referenceDate, "referenceDate is required");
        if (plan == null) {
            invalid("Plan is required");
        }

        String overview = requireText(plan.overview(), "overview");
        if (overview.length() > MAX_OVERVIEW_LENGTH) {
            invalid("overview exceeds " + MAX_OVERVIEW_LENGTH + " characters");
        }

        List<ProjectPlan.ExistingIssue> existingIssues = validateExistingIssues(
                plan.existingIssues(),
                activeIssueIds
        );

        List<ProjectPlan.Item> items = plan.items();
        if (items == null) {
            invalid("items is required");
        }
        if (items.size() > MAX_ITEMS) {
            invalid("Plan must contain at most " + MAX_ITEMS + " items");
        }
        if (items.isEmpty() && existingIssues.isEmpty()) {
            invalid("Plan must reuse an existing issue or add at least one item");
        }

        Set<String> clientItemIds = new HashSet<>();
        List<ProjectPlan.Item> normalizedItems = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            ProjectPlan.Item item = items.get(index);
            if (item == null) {
                invalid("Item at index " + index + " is missing");
            }
            normalizedItems.add(validateItem(
                    item,
                    index,
                    clientItemIds,
                    activeMemberUserIds,
                    referenceDate
            ));
        }

        return new ProjectPlan(overview, existingIssues, List.copyOf(normalizedItems));
    }

    // Older saved plans have no existingIssues at all, which reads as none.
    private List<ProjectPlan.ExistingIssue> validateExistingIssues(
            List<ProjectPlan.ExistingIssue> existingIssues,
            Set<UUID> activeIssueIds
    ) {
        if (existingIssues == null) {
            return List.of();
        }
        if (existingIssues.size() > MAX_EXISTING_ISSUES) {
            invalid("Plan must name at most " + MAX_EXISTING_ISSUES + " existing issues");
        }
        Set<UUID> seen = new HashSet<>();
        List<ProjectPlan.ExistingIssue> normalized = new ArrayList<>();
        for (int index = 0; index < existingIssues.size(); index++) {
            ProjectPlan.ExistingIssue existing = existingIssues.get(index);
            if (existing == null || existing.issueId() == null) {
                invalid("issueId at existing issue index " + index + " is required");
            }
            UUID issueId = existing.issueId();
            if (!seen.add(issueId)) {
                invalid("existingIssues must not name issue " + issueId + " twice");
            }
            // The id came from the model: it must be an issue of this project that is still active.
            if (!activeIssueIds.contains(issueId)) {
                invalid("existing issue " + issueId + " is not an active issue of this project");
            }
            String reason = requireText(existing.reason(), "reason at existing issue index " + index);
            if (reason.length() > MAX_REASON_LENGTH) {
                invalid("reason at existing issue index " + index + " exceeds "
                        + MAX_REASON_LENGTH + " characters");
            }
            normalized.add(new ProjectPlan.ExistingIssue(issueId, reason));
        }
        return List.copyOf(normalized);
    }

    private ProjectPlan.Item validateItem(
            ProjectPlan.Item item,
            int index,
            Set<String> clientItemIds,
            Set<UUID> activeMemberUserIds,
            LocalDate referenceDate
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
        if (dueDate != null && dueDate.isBefore(referenceDate)) {
            invalid("dueDate of item " + clientItemId + " is in the past");
        }
        if (dueDate != null && dueDate.isAfter(referenceDate.plusDays(MAX_DAYS_AHEAD))) {
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
