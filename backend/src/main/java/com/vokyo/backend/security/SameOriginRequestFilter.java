package com.vokyo.backend.security;

import com.vokyo.backend.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Rejects browser cross-site writes while retaining headerless CLI access. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SameOriginRequestFilter extends OncePerRequestFilter {

    private final ApiErrorWriter errorWriter;

    public SameOriginRequestFilter(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || isSafeMethod(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && "cross-site".equals(fetchSite.toLowerCase(Locale.ROOT))) {
            reject(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !isSameOrigin(origin, request)) {
            reject(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        errorWriter.write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "CROSS_SITE_REQUEST_REJECTED",
                "Cross-site request rejected"
        );
    }

    private boolean isSameOrigin(String origin, HttpServletRequest request) {
        try {
            URI parsed = new URI(origin);
            if (parsed.getUserInfo() != null || parsed.getHost() == null || parsed.getScheme() == null) {
                return false;
            }
            return parsed.getScheme().equalsIgnoreCase(request.getScheme())
                    && parsed.getHost().equalsIgnoreCase(request.getServerName())
                    && effectivePort(parsed.getScheme(), parsed.getPort())
                    == effectivePort(request.getScheme(), request.getServerPort());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
