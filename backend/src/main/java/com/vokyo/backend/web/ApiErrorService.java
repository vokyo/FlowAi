package com.vokyo.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ApiErrorService {

    private final ApiObservability observability;

    public ApiErrorService(ApiObservability observability) {
        this.observability = observability;
    }

    public ApiErrorResponse create(
            HttpServletRequest request,
            int status,
            String code,
            String message
    ) {
        return create(request, status, code, message, Map.of());
    }

    public ApiErrorResponse create(
            HttpServletRequest request,
            int status,
            String code,
            String message,
            Map<String, String> fieldErrors
    ) {
        observability.recordException(code, status);
        Map<String, String> safeFieldErrors = fieldErrors == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(fieldErrors));
        return new ApiErrorResponse(
                code,
                message,
                safeFieldErrors,
                TraceIds.fromRequest(request)
        );
    }
}
