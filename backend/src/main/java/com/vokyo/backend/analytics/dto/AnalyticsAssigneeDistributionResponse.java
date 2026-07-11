package com.vokyo.backend.analytics.dto;

import java.util.UUID;

public record AnalyticsAssigneeDistributionResponse(
        UUID userId,
        String displayName,
        String email,
        long count
) {
}
