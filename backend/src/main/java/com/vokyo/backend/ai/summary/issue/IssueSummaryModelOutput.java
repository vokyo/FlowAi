package com.vokyo.backend.ai.summary.issue;

import java.util.List;

public record IssueSummaryModelOutput(
        String summary,
        List<String> decisions,
        List<String> openQuestions,
        List<String> blockers,
        List<String> nextActions
) {
}
