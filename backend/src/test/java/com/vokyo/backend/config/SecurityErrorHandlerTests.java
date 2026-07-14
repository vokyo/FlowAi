package com.vokyo.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.web.ApiErrorService;
import com.vokyo.backend.web.ApiErrorWriter;
import com.vokyo.backend.web.ApiObservability;
import com.vokyo.backend.web.TraceIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorHandlerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private SecurityConfig securityConfig;
    private ApiErrorWriter errorWriter;
    private ApiObservability observability;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        observability = new ApiObservability(meterRegistry);
        errorWriter = new ApiErrorWriter(
                objectMapper,
                new ApiErrorService(observability)
        );
        securityConfig = new SecurityConfig();
    }

    @Test
    void authenticationEntryPointReturnsJsonTraceAndRecordsFailure() throws Exception {
        MockHttpServletRequest request = requestWithTrace("security-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        securityConfig.apiAuthenticationEntryPoint(errorWriter, observability)
                .commence(request, response, new BadCredentialsException("raw JWT detail"));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(TraceIds.HEADER)).isEqualTo("security-trace-123");
        assertThat(body.get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(body.get("message").asText()).isEqualTo("Authentication is required");
        assertThat(body.get("fieldErrors").isEmpty()).isTrue();
        assertThat(body.get("traceId").asText()).isEqualTo("security-trace-123");
        assertThat(body.toString()).doesNotContain("raw JWT detail");
        assertThat(meterRegistry.find("flowai.authentication.failures")
                .tag("reason", "token")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void accessDeniedHandlerReturnsSameStableShape() throws Exception {
        MockHttpServletRequest request = requestWithTrace("denied-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        securityConfig.apiAccessDeniedHandler(errorWriter)
                .handle(request, response, new AccessDeniedException("internal authority"));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(body.get("message").asText()).isEqualTo("Access is denied");
        assertThat(body.get("traceId").asText()).isEqualTo("denied-trace-123");
        assertThat(body.toString()).doesNotContain("internal authority");
    }

    private MockHttpServletRequest requestWithTrace(String traceId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.setAttribute(TraceIds.REQUEST_ATTRIBUTE, traceId);
        return request;
    }
}
