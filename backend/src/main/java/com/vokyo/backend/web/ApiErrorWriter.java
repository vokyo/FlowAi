package com.vokyo.backend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;
    private final ApiErrorService errorService;

    public ApiErrorWriter(ObjectMapper objectMapper, ApiErrorService errorService) {
        this.objectMapper = objectMapper;
        this.errorService = errorService;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        write(request, response, status, code, message, Map.of());
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message,
            Map<String, String> fieldErrors
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ApiErrorResponse body = errorService.create(
                request,
                status,
                code,
                message,
                fieldErrors
        );
        response.resetBuffer();
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(TraceIds.HEADER, body.traceId());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
