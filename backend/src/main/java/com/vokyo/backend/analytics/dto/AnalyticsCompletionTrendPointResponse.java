package com.vokyo.backend.analytics.dto;

import java.time.LocalDate;

public record AnalyticsCompletionTrendPointResponse(
        LocalDate date,
        long count
) {
}
