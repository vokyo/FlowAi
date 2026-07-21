package com.vokyo.backend.ai.breakdown;

import com.vokyo.backend.issue.IssuePriority;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueBreakdownValidatorTests {

    private final IssueBreakdownValidator validator = new IssueBreakdownValidator();
    private final UUID allowedLabelId = UUID.randomUUID();
    private final UUID allowedAssigneeId = UUID.randomUUID();

    @Test
    void normalizesFieldsAndRemovesUnavailableOptionalCandidates() {
        UUID unknownLabelId = UUID.randomUUID();
        IssueBreakdownResult result = new IssueBreakdownResult(
                "  Delivery plan  ",
                List.of(
                        item(
                                " item-1 ",
                                " Define API ",
                                List.of(allowedLabelId, unknownLabelId, allowedLabelId),
                                UUID.randomUUID(),
                                List.of()
                        ),
                        item(
                                "item-2",
                                "Implement API",
                                null,
                                allowedAssigneeId,
                                List.of(" item-1 ", "item-1")
                        )
                ),
                List.of(" Existing warning ", "Existing warning", " ")
        );

        IssueBreakdownResult validated = validator.validate(result, context(4));

        assertThat(validated.overview()).isEqualTo("Delivery plan");
        assertThat(validated.items().get(0).clientItemId()).isEqualTo("item-1");
        assertThat(validated.items().get(0).title()).isEqualTo("Define API");
        assertThat(validated.items().get(0).suggestedLabelIds()).containsExactly(allowedLabelId);
        assertThat(validated.items().get(0).suggestedAssigneeUserId()).isNull();
        assertThat(validated.items().get(1).suggestedAssigneeUserId()).isEqualTo(allowedAssigneeId);
        assertThat(validated.items().get(1).dependsOnClientItemIds()).containsExactly("item-1");
        assertThat(validated.warnings())
                .containsExactly(
                        "Existing warning",
                        "Removed an unavailable label suggestion from item-1",
                        "Removed an unavailable assignee suggestion from item-1"
                );
    }

    @Test
    void rejectsInvalidItemCountAndFieldLengths() {
        assertThatThrownBy(() -> validator.validate(
                new IssueBreakdownResult(null, List.of(item("item-1", "Only one", List.of(), null, List.of())), List.of()),
                context(4)
        )).isInstanceOf(IssueBreakdownValidationException.class)
                .hasMessageContaining("between 2 and 4");

        String oversizedTitle = "x".repeat(241);
        assertThatThrownBy(() -> validator.validate(
                result(
                        item("item-1", oversizedTitle, List.of(), null, List.of()),
                        item("item-2", "Valid", List.of(), null, List.of())
                ),
                context(4)
        )).isInstanceOf(IssueBreakdownValidationException.class)
                .hasMessageContaining("240");

        String oversizedDescription = "x".repeat(10_001);
        IssueBreakdownResult.Item invalidDescription = new IssueBreakdownResult.Item(
                "item-1", "Valid", oversizedDescription, IssuePriority.HIGH,
                List.of(), List.of(), null, null, List.of()
        );
        assertThatThrownBy(() -> validator.validate(
                result(invalidDescription, item("item-2", "Valid", List.of(), null, List.of())),
                context(4)
        )).isInstanceOf(IssueBreakdownValidationException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void rejectsUnknownSelfAndCyclicDependencies() {
        assertInvalidDependencies(List.of("missing"), List.of());
        assertInvalidDependencies(List.of("item-1"), List.of());

        assertThatThrownBy(() -> validator.validate(
                result(
                        item("item-1", "First", List.of(), null, List.of("item-2")),
                        item("item-2", "Second", List.of(), null, List.of("item-1"))
                ),
                context(4)
        )).isInstanceOf(IssueBreakdownValidationException.class)
                .hasMessageContaining("cycle");
    }

    private void assertInvalidDependencies(List<String> first, List<String> second) {
        assertThatThrownBy(() -> validator.validate(
                result(
                        item("item-1", "First", List.of(), null, first),
                        item("item-2", "Second", List.of(), null, second)
                ),
                context(4)
        )).isInstanceOf(IssueBreakdownValidationException.class);
    }

    private IssueBreakdownResult result(IssueBreakdownResult.Item... items) {
        return new IssueBreakdownResult("Overview", List.of(items), List.of());
    }

    private IssueBreakdownResult.Item item(
            String id,
            String title,
            List<UUID> labelIds,
            UUID assigneeId,
            List<String> dependencies
    ) {
        return new IssueBreakdownResult.Item(
                id,
                title,
                "Description",
                IssuePriority.HIGH,
                List.of("Done", " Done ", " "),
                labelIds,
                assigneeId,
                LocalDate.of(2026, 8, 1),
                dependencies
        );
    }

    private IssueBreakdownContext context(int maxItems) {
        UUID issueId = UUID.randomUUID();
        UUID workflowStateId = UUID.randomUUID();
        return new IssueBreakdownContext(
                new IssueBreakdownContext.ProjectContext("Project", "Description"),
                new IssueBreakdownContext.SourceIssueContext(
                        issueId,
                        "Source issue",
                        "Source description",
                        "TODO",
                        "HIGH",
                        null,
                        new IssueBreakdownContext.WorkflowStateContext(workflowStateId, "Todo", "TODO"),
                        null,
                        List.of()
                ),
                null,
                maxItems,
                List.of(),
                List.of(),
                new IssueBreakdownContext.AllowedCandidates(
                        List.of(new IssueBreakdownContext.LabelCandidate(allowedLabelId, "Backend", "#000000")),
                        List.of(new IssueBreakdownContext.MemberCandidate(allowedAssigneeId, "Member", "MEMBER")),
                        List.of(new IssueBreakdownContext.WorkflowStateCandidate(workflowStateId, "Todo", "TODO"))
                ),
                new IssueBreakdownContext.SourceStats(0, 0, false, false, false)
        );
    }
}
