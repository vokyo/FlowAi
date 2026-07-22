package com.vokyo.backend.ai.summary.project;

import java.util.List;

public record ProjectSummaryResult(
        String executiveSummary,
        List<String> progressHighlights,
        List<String> currentRisks,
        List<String> blockers,
        List<String> workloadObservations,
        List<String> recommendedNextActions,
        ProjectSummaryContext.SourceStats sourceStats
) {
}
