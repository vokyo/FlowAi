package com.vokyo.backend.ai.summary.issue;

import com.vokyo.backend.ai.summary.AiSummaryValidationException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

@Component
public class IssueSummaryValidator {

    private static final int MAX_SUMMARY_LENGTH = 800;
    private static final int MAX_LIST_ITEMS = 10;
    private static final int MAX_LIST_ITEM_LENGTH = 500;

    public IssueSummaryModelOutput validate(IssueSummaryModelOutput output) {
        if (output == null) invalid("Issue summary output is required");
        String summary = normalize(output.summary());
        if (summary == null) invalid("Summary is required");
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            invalid("Summary must be at most 800 characters");
        }
        return new IssueSummaryModelOutput(
                summary,
                normalizeList(output.decisions(), "decisions"),
                normalizeList(output.openQuestions(), "openQuestions"),
                normalizeList(output.blockers(), "blockers"),
                normalizeList(output.nextActions(), "nextActions")
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
