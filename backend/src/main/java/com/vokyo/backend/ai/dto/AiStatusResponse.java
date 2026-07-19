package com.vokyo.backend.ai.dto;

public record AiStatusResponse(
        boolean enabled,
        boolean breakdownAvailable,
        boolean issueSummaryAvailable,
        boolean projectSummaryAvailable,
        boolean agentAvailable,
        String disabledReason
) {
}