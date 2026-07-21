package com.vokyo.backend.ai.breakdown.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record IssueBreakdownRequest(
        @Size(max = 2000) String instruction,
        @Min(2) @Max(8) Integer maxItems,
        boolean includeComments,
        boolean includeActivity
) {
}
