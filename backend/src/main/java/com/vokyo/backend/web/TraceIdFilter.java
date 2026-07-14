package com.vokyo.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = TraceIds.acceptOrGenerate(request.getHeader(TraceIds.HEADER));
        request.setAttribute(TraceIds.REQUEST_ATTRIBUTE, traceId);
        response.setHeader(TraceIds.HEADER, traceId);

        try (MDC.MDCCloseable ignored = MDC.putCloseable(TraceIds.MDC_KEY, traceId)) {
            filterChain.doFilter(request, response);
        }
    }
}
