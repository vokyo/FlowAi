package com.vokyo.backend.analytics.dto;

public record AnalyticsStatusDistributionResponse(
        String category,
        long count
) {
}
