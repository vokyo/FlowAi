package com.vokyo.backend.web;

import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        Map<String, String> fieldErrors,
        String traceId
) {
}
