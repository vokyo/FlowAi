package com.vokyo.backend.security.ratelimit;

import com.vokyo.backend.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ApiErrorWriter errorWriter;

    public RateLimitFilter(RateLimitService rateLimitService, ApiErrorWriter errorWriter) {
        this.rateLimitService = rateLimitService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return policyFor(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitPolicy policy = policyFor(request);
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimitService.consume(policy, clientIdentity(request));
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        errorWriter.write(
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "RATE_LIMITED",
                "Too many requests"
        );
    }

    private String clientIdentity(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private RateLimitPolicy policyFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equals(method) && "/api/auth/login".equals(path)) {
            return RateLimitPolicy.LOGIN_IP;
        }
        if ("POST".equals(method)
                && ("/api/auth/register".equals(path)
                || "/api/auth/register-with-invitation".equals(path))) {
            return RateLimitPolicy.REGISTRATION_IP;
        }
        if ("POST".equals(method) && "/api/auth/refresh".equals(path)) {
            return RateLimitPolicy.REFRESH_IP;
        }
        if ("GET".equals(method)
                && path.matches("^/api/workspace-invitations/[^/]+$")) {
            return RateLimitPolicy.INVITATION_PREVIEW_IP;
        }
        return null;
    }
}
