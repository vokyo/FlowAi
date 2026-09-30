package com.vokyo.backend.ai.plan;

import com.vokyo.backend.issue.IssuePriority;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPlanValidatorTests {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-01");

    private final ProjectPlanValidator validator = new ProjectPlanValidator();
    private final UUID member = UUID.randomUUID();
    private final Set<UUID> activeMembers = Set.of(member);

    @Test
    void acceptsAValidPlanAndTrimsItsText() {
        ProjectPlan plan = new ProjectPlan(
                "  Clear the login debt  ",
                List.of(
                        item(" item-1 ", " Remove the legacy session table ", member, TODAY),
                        item("item-2", "Add refresh token tests", null, TODAY.plusDays(14)),
                        item("item-3", "Document the login flow", member, null)
                )
        );

        ProjectPlan validated = validator.validate(plan, activeMembers, TODAY);

        assertThat(validated.overview()).isEqualTo("Clear the login debt");
        assertThat(validated.items())
                .extracting(ProjectPlan.Item::clientItemId)
                .containsExactly("item-1", "item-2", "item-3");
        assertThat(validated.items().getFirst().title()).isEqualTo("Remove the legacy session table");
        assertThat(validated.items().getFirst().suggestedAssigneeUserId()).isEqualTo(member);
    }

    @Test
    void requiresThreeToFiveItems() {
        assertInvalid(planWithItems(2), "Plan must contain between 3 and 5 items");
        assertInvalid(planWithItems(6), "Plan must contain between 3 and 5 items");
        assertInvalid(new ProjectPlan("Overview", null), "Plan must contain between 3 and 5 items");
        assertThat(validator.validate(planWithItems(5), activeMembers, TODAY).items()).hasSize(5);
    }

    @Test
    void requiresAnOverviewAndUniqueItemIds() {
        assertInvalid(
                new ProjectPlan(" ", planWithItems(3).items()),
                "overview is required"
        );
        assertInvalid(
                withItem(1, item("item-1", "Duplicate id", null, null)),
                "clientItemId values must be unique"
        );
    }

    @Test
    void enforcesTextLengthsAndPriority() {
        assertInvalid(
                withItem(0, item("item-1", "x".repeat(241), null, null)),
                "title at item index 0 exceeds 240 characters"
        );
        assertInvalid(
                withItem(0, item("item-1", " ", null, null)),
                "title at item index 0 is required"
        );
        assertInvalid(
                withItem(2, new ProjectPlan.Item(
                        "item-3", "Title", "x".repeat(10_001), IssuePriority.LOW, null, null)),
                "description at item index 2 exceeds 10000 characters"
        );
        assertInvalid(
                withItem(1, new ProjectPlan.Item("item-2", "Title", null, null, null, null)),
                "priority at item index 1 is required"
        );
    }

    @Test
    void assigneesMustBeActiveProjectMembers() {
        UUID stranger = UUID.randomUUID();

        assertInvalid(
                withItem(0, item("item-1", "Title", stranger, null)),
                "suggestedAssigneeUserId of item item-1 is not an active project member"
        );
    }

    @Test
    void dueDatesMustFallBetweenTodayAndAYearAhead() {
        assertInvalid(
                withItem(0, item("item-1", "Title", null, TODAY.minusDays(1))),
                "dueDate of item item-1 is in the past"
        );
        assertInvalid(
                withItem(0, item("item-1", "Title", null, TODAY.plusDays(366))),
                "dueDate of item item-1 is more than 365 days away"
        );
        assertThat(validator.validate(
                withItem(0, item("item-1", "Title", null, TODAY.plusDays(365))),
                activeMembers,
                TODAY
        ).items().getFirst().dueDate()).isEqualTo(TODAY.plusDays(365));
    }

    private void assertInvalid(ProjectPlan plan, String message) {
        assertThatThrownBy(() -> validator.validate(plan, activeMembers, TODAY))
                .isInstanceOf(ProjectPlanValidationException.class)
                .hasMessage(message);
    }

    private ProjectPlan withItem(int index, ProjectPlan.Item replacement) {
        List<ProjectPlan.Item> items = new ArrayList<>(planWithItems(3).items());
        items.set(index, replacement);
        return new ProjectPlan("Overview", items);
    }

    private ProjectPlan planWithItems(int count) {
        List<ProjectPlan.Item> items = new ArrayList<>();
        for (int number = 1; number <= count; number++) {
            items.add(item("item-" + number, "Task " + number, null, null));
        }
        return new ProjectPlan("Overview", items);
    }

    private ProjectPlan.Item item(String clientItemId, String title, UUID assignee, LocalDate dueDate) {
        return new ProjectPlan.Item(
                clientItemId,
                title,
                "Description",
                IssuePriority.MEDIUM,
                assignee,
                dueDate
        );
    }
}
