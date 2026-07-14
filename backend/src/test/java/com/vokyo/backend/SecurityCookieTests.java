package com.vokyo.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.auth.RefreshTokenCookieService;
import com.vokyo.backend.security.JwtProperties;
import com.vokyo.backend.security.RefreshTokenCookieProperties;
import com.vokyo.backend.security.SameOriginRequestFilter;
import com.vokyo.backend.web.ApiErrorService;
import com.vokyo.backend.web.ApiErrorWriter;
import com.vokyo.backend.web.ApiObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityCookieTests {

    @Test
    void refreshCookieIsHttpOnlyStrictAndUsesConfiguredSecurity() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(
                new RefreshTokenCookieProperties(true),
                new JwtProperties("01234567890123456789012345678901", Duration.ofMinutes(15), Duration.ofDays(7))
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.write(response, "plain-refresh-token");

        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie)
                .contains("flowai_refresh_token=plain-refresh-token")
                .contains("Path=/api")
                .contains("Max-Age=604800")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void clearCookieExpiresTheSameCookie() {
        RefreshTokenCookieService service = new RefreshTokenCookieService(
                new RefreshTokenCookieProperties(false),
                new JwtProperties("01234567890123456789012345678901", Duration.ofMinutes(15), Duration.ofDays(7))
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("flowai_refresh_token=")
                .contains("Path=/api")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void sameOriginFilterRejectsCrossSiteBrowserWrites() throws Exception {
        SameOriginRequestFilter filter = new SameOriginRequestFilter(errorWriter());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("CROSS_SITE_REQUEST_REJECTED");
        assertThat(body.get("message").asText()).isEqualTo("Cross-site request rejected");
        assertThat(body.get("fieldErrors").isEmpty()).isTrue();
        assertThat(body.get("traceId").asText()).isNotBlank();
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(body.get("traceId").asText());
    }

    @Test
    void sameOriginFilterAllowsMatchingOriginAndHeaderlessClients() throws Exception {
        SameOriginRequestFilter filter = new SameOriginRequestFilter(errorWriter());

        MockHttpServletRequest browserRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        browserRequest.setScheme("https");
        browserRequest.setServerName("flowai.example");
        browserRequest.setServerPort(443);
        browserRequest.addHeader("Origin", "https://flowai.example");
        MockHttpServletResponse browserResponse = new MockHttpServletResponse();
        MockFilterChain browserChain = new MockFilterChain();
        filter.doFilter(browserRequest, browserResponse, browserChain);

        MockHttpServletRequest cliRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse cliResponse = new MockHttpServletResponse();
        MockFilterChain cliChain = new MockFilterChain();
        filter.doFilter(cliRequest, cliResponse, cliChain);

        assertThat(browserChain.getRequest()).isSameAs(browserRequest);
        assertThat(cliChain.getRequest()).isSameAs(cliRequest);
    }

    @Test
    void sameOriginFilterIncludesNonDefaultPorts() throws Exception {
        SameOriginRequestFilter filter = new SameOriginRequestFilter(errorWriter());
        MockHttpServletRequest matchingRequest = new MockHttpServletRequest("POST", "/api/auth/refresh");
        matchingRequest.setScheme("http");
        matchingRequest.setServerName("flowai.example");
        matchingRequest.setServerPort(8080);
        matchingRequest.addHeader("Origin", "http://flowai.example:8080");
        MockFilterChain matchingChain = new MockFilterChain();

        filter.doFilter(matchingRequest, new MockHttpServletResponse(), matchingChain);

        MockHttpServletRequest mismatchedRequest = new MockHttpServletRequest("POST", "/api/auth/refresh");
        mismatchedRequest.setScheme("http");
        mismatchedRequest.setServerName("flowai.example");
        mismatchedRequest.setServerPort(80);
        mismatchedRequest.addHeader("Origin", "http://flowai.example:8080");
        MockHttpServletResponse mismatchedResponse = new MockHttpServletResponse();
        MockFilterChain mismatchedChain = new MockFilterChain();

        filter.doFilter(mismatchedRequest, mismatchedResponse, mismatchedChain);

        assertThat(matchingChain.getRequest()).isSameAs(matchingRequest);
        assertThat(mismatchedResponse.getStatus()).isEqualTo(403);
        assertThat(mismatchedChain.getRequest()).isNull();
    }

    private ApiErrorWriter errorWriter() {
        ApiErrorService errorService = new ApiErrorService(
                new ApiObservability(new SimpleMeterRegistry())
        );
        return new ApiErrorWriter(new ObjectMapper(), errorService);
    }
}
