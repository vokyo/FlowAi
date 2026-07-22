package com.vokyo.backend.ai.summary.project;

import com.vokyo.backend.ai.summary.AiSummaryValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectSummaryValidatorTests {
    private final ProjectSummaryValidator validator = new ProjectSummaryValidator();

    @Test
    void normalizesNullListsAndDuplicateItems() {
        ProjectSummaryModelOutput result = validator.validate(new ProjectSummaryModelOutput(
                "  Executive summary  ",
                List.of("Done", "Done", "  "),
                null,
                null,
                null,
                null
        ));

        assertThat(result.executiveSummary()).isEqualTo("Executive summary");
        assertThat(result.progressHighlights()).containsExactly("Done");
        assertThat(result.currentRisks()).isEmpty();
        assertThat(result.recommendedNextActions()).isEmpty();
    }

    @Test
    void rejectsMissingOrOversizedCoreSummary() {
        assertThatThrownBy(() -> validator.validate(new ProjectSummaryModelOutput(
                " ", null, null, null, null, null
        ))).isInstanceOf(AiSummaryValidationException.class);
        assertThatThrownBy(() -> validator.validate(new ProjectSummaryModelOutput(
                "x".repeat(1201), null, null, null, null, null
        ))).isInstanceOf(AiSummaryValidationException.class);
    }
}
