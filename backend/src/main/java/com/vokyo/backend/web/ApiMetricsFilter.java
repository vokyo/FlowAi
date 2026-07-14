package com.vokyo.backend.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiMetricsFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;

    public ApiMetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            filterChain.doFilter(request, response);
        } finally {
            sample.stop(Timer.builder("flowai.api.request.duration")
                    .description("FlowAI API request latency")
                    .tag("method", normalizedMethod(request.getMethod()))
                    .tag("outcome", outcome(response.getStatus()))
                    .register(meterRegistry));
        }
    }

    private String normalizedMethod(String method) {
        return switch (method) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> method;
            default -> "OTHER";
        };
    }

    private String outcome(int status) {
        if (status >= 100 && status < 600) {
            return (status / 100) + "xx";
        }
        return "unknown";
    }
}
