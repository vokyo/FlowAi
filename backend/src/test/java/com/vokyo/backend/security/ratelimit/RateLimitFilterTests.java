package com.vokyo.backend.security.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.web.ApiErrorService;
import com.vokyo.backend.web.ApiErrorWriter;
import com.vokyo.backend.web.ApiObservability;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTests {

    private static final String DEFAULT_REMOTE_ADDRESS = "192.0.2.10";

    private RateLimitFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitService rateLimitService = new RateLimitService(
                new RateLimitProperties(true, 1_000, Duration.ofHours(2)),
                new FixedTimeMeter(),
                meterRegistry
        );
        filter = new RateLimitFilter(
                rateLimitService,
                new ApiErrorWriter(
                        objectMapper,
                        new ApiErrorService(new ApiObservability(meterRegistry))
                )
        );
    }

    @Test
    void bothRegistrationRoutesShareOneIpBucketAndReturnStable429Response() throws Exception {
        for (int attempt = 0; attempt < RateLimitPolicy.REGISTRATION_IP.capacity(); attempt++) {
            String path = attempt % 2 == 0
                    ? "/api/auth/register"
                    : "/api/auth/register-with-invitation";
            assertThat(perform("POST", path, DEFAULT_REMOTE_ADDRESS, null).getStatus())
                    .isEqualTo(HttpStatus.NO_CONTENT.value());
        }

        MockHttpServletResponse rejected = perform(
                "POST",
                "/api/auth/register-with-invitation",
                DEFAULT_REMOTE_ADDRESS,
                null
        );

        assertThat(rejected.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("360");
        assertThat(MediaType.parseMediaType(rejected.getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        JsonNode body = objectMapper.readTree(rejected.getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("message").asText()).isEqualTo("Too many requests");
        assertThat(body.get("fieldErrors").isObject()).isTrue();
        assertThat(body.get("fieldErrors").isEmpty()).isTrue();
        assertThat(body.get("traceId").asText()).isNotBlank();
        assertThat(rejected.getHeader("X-Trace-Id")).isEqualTo(body.get("traceId").asText());
    }

    @Test
    void invitationPreviewOnlyMatchesGetWithExactlyOneTokenSegment() throws Exception {
        String previewPath = "/api/workspace-invitations/one-token";
        for (int attempt = 0; attempt < RateLimitPolicy.INVITATION_PREVIEW_IP.capacity(); attempt++) {
            assertThat(perform("GET", previewPath, DEFAULT_REMOTE_ADDRESS, null).getStatus())
                    .isEqualTo(HttpStatus.NO_CONTENT.value());
        }
        assertThat(perform("GET", previewPath, DEFAULT_REMOTE_ADDRESS, null).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        assertThat(perform(
                "GET",
                "/api/workspace-invitations/one-token/extra",
                DEFAULT_REMOTE_ADDRESS,
                null
        ).getStatus()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(perform(
                "GET",
                "/api/workspace-invitations/",
                DEFAULT_REMOTE_ADDRESS,
                null
        ).getStatus()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(perform(
                "POST",
                previewPath,
                DEFAULT_REMOTE_ADDRESS,
                null
        ).getStatus()).isEqualTo(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void forwardedForHeaderCannotOverrideRemoteAddressIdentity() throws Exception {
        for (int attempt = 0; attempt < RateLimitPolicy.LOGIN_IP.capacity(); attempt++) {
            assertThat(perform(
                    "POST",
                    "/api/auth/login",
                    "198.51.100.20",
                    "203.0.113." + attempt
            ).getStatus()).isEqualTo(HttpStatus.NO_CONTENT.value());
        }

        assertThat(perform(
                "POST",
                "/api/auth/login",
                "198.51.100.20",
                "203.0.113.250"
        ).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        assertThat(perform(
                "POST",
                "/api/auth/login",
                "198.51.100.21",
                "203.0.113.250"
        ).getStatus()).isEqualTo(HttpStatus.NO_CONTENT.value());
    }

    private MockHttpServletResponse perform(
            String method,
            String path,
            String remoteAddress,
            String forwardedFor
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(HttpStatus.NO_CONTENT.value());

        filter.doFilter(request, response, chain);
        return response;
    }

    private static final class FixedTimeMeter implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            return Duration.ofDays(1).toNanos();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }
}
