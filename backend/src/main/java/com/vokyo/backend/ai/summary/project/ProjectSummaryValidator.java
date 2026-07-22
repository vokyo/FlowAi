package com.vokyo.backend.ai.summary.project;

import com.vokyo.backend.ai.summary.AiSummaryValidationException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

@Component
public class ProjectSummaryValidator {

    private static final int MAX_SUMMARY_LENGTH = 1200;
    private static final int MAX_LIST_ITEMS = 10;
    private static final int MAX_LIST_ITEM_LENGTH = 500;

    public ProjectSummaryModelOutput validate(ProjectSummaryModelOutput output) {
        if (output == null) invalid("Project summary output is required");
        String executiveSummary = normalize(output.executiveSummary());
        if (executiveSummary == null) invalid("Executive summary is required");
        if (executiveSummary.length() > MAX_SUMMARY_LENGTH) {
            invalid("Executive summary must be at most 1200 characters");
        }
        return new ProjectSummaryModelOutput(
                executiveSummary,
                normalizeList(output.progressHighlights(), "progressHighlights"),
                normalizeList(output.currentRisks(), "currentRisks"),
                normalizeList(output.blockers(), "blockers"),
                normalizeList(output.workloadObservations(), "workloadObservations"),
                normalizeList(output.recommendedNextActions(), "recommendedNextActions")
        );
    }

    private List<String> normalizeList(List<String> values, String field) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String text = normalize(value);
                if (text == null) continue;
                if (text.length() > MAX_LIST_ITEM_LENGTH) {
                    invalid(field + " items must be at most 500 characters");
                }
                normalized.add(text);
            }
        }
        if (normalized.size() > MAX_LIST_ITEMS) {
            invalid(field + " must contain at most 10 items");
        }
        return List.copyOf(normalized);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void invalid(String message) {
        throw new AiSummaryValidationException(message);
    }
}
