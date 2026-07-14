package com.vokyo.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String REQUEST_ATTRIBUTE = TraceIds.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,63}");

    private TraceIds() {
    }

    public static String fromRequest(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        String mdcTraceId = MDC.get(MDC_KEY);
        return mdcTraceId == null || mdcTraceId.isBlank() ? generate() : mdcTraceId;
    }

    public static String acceptOrGenerate(String candidate) {
        if (candidate != null) {
            String normalized = candidate.trim();
            if (VALID_TRACE_ID.matcher(normalized).matches()) {
                return normalized;
            }
        }
        return generate();
    }

    private static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
