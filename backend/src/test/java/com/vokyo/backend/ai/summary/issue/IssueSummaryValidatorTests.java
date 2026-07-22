package com.vokyo.backend.ai.summary.issue;

import com.vokyo.backend.ai.summary.AiSummaryValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueSummaryValidatorTests {

    private final IssueSummaryValidator validator = new IssueSummaryValidator();

    @Test
    void normalizesNullEmptyAndDuplicateLists() {
        IssueSummaryModelOutput result = validator.validate(
                new IssueSummaryModelOutput(
                        "  Current state  ",
                        List.of(" Decision ", "Decision", " "),
                        null,
                        List.of(),
                        List.of("Next")
                )
        );

        assertThat(result.summary()).isEqualTo("Current state");
        assertThat(result.decisions()).containsExactly("Decision");
        assertThat(result.openQuestions()).isEmpty();
        assertThat(result.blockers()).isEmpty();
        assertThat(result.nextActions()).containsExactly("Next");
    }

    @Test
    void rejectsMissingOrOversizedCoreSummary() {
        assertThatThrownBy(() -> validator.validate(
                new IssueSummaryModelOutput(" ", null, null, null, null)
        )).isInstanceOf(AiSummaryValidationException.class);

        assertThatThrownBy(() -> validator.validate(
                new IssueSummaryModelOutput("x".repeat(801), null, null, null, null)
        )).isInstanceOf(AiSummaryValidationException.class);
    }
}
