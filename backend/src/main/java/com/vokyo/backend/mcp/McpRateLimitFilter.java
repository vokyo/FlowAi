package com.vokyo.backend.mcp;

import com.vokyo.backend.security.ratelimit.RateLimitDecision;
import com.vokyo.backend.security.ratelimit.RateLimitService;
import com.vokyo.backend.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limits each personal access token's requests to the MCP endpoint. Runs after the
 * token is authenticated, inside the MCP security chain only, so it is not a bean:
 * Spring Boot would otherwise put it in front of every request.
 */
class McpRateLimitFilter extends OncePerRequestFilter {

    private static final String POLICY_NAME = "mcp_token";

    private final RateLimitService rateLimitService;
    private final McpProperties.RateLimit limit;
    private final ApiErrorWriter errorWriter;

    McpRateLimitFilter(RateLimitService rateLimitService, McpProperties.RateLimit limit, ApiErrorWriter errorWriter) {
        this.rateLimitService = rateLimitService;
        this.limit = limit;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var caller = PersonalAccessTokenIntrospector.caller(SecurityContextHolder.getContext().getAuthentication());
        if (caller.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimitService.consume(
                POLICY_NAME, limit.capacity(), limit.window(), caller.get().tokenId().toString());
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED", "Too many requests");
    }
}
