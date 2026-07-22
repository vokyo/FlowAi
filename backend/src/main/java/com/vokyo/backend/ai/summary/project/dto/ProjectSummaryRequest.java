package com.vokyo.backend.ai.summary.project.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record ProjectSummaryRequest(
        Integer rangeDays,
        @Size(max = 2000)
        String focus
) {
    private static final Set<Integer> SUPPORTED_RANGES = Set.of(7, 30, 90);

    public int effectiveRangeDays() {
        return rangeDays == null ? 30 : rangeDays;
    }

    public String normalizedFocus() {
        return focus == null || focus.isBlank() ? null : focus.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "rangeDays must be 7, 30, or 90")
    public boolean isRangeSupported() {
        return rangeDays == null || SUPPORTED_RANGES.contains(rangeDays);
    }
}
