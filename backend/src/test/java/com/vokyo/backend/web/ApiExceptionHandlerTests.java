package com.vokyo.backend.web;

import com.vokyo.backend.security.ratelimit.RateLimitExceededException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTests {

    private SimpleMeterRegistry meterRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ApiObservability observability = new ApiObservability(meterRegistry);
        ApiExceptionHandler handler = new ApiExceptionHandler(
                new ApiErrorService(observability),
                observability
        );
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorTestController())
                .setControllerAdvice(handler)
                .setValidator(validator)
                .addFilters(new TraceIdFilter(), new ApiMetricsFilter(meterRegistry))
                .build();
    }

    @Test
    void validationUsesStableShapeAndPropagatesValidTraceId() throws Exception {
        String traceId = "client-trace-1234";

        mockMvc.perform(post("/api/test/errors/validation")
                        .header(TraceIds.HEADER, traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(TraceIds.HEADER, traceId))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void methodValidationUsesTheSameStableShape() throws Exception {
        mockMvc.perform(get("/api/test/errors/method-validation")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.limit").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void invalidCursorGetsDedicatedCodeAndGeneratedTraceId() throws Exception {
        var result = mockMvc.perform(get("/api/test/errors/cursor")
                        .header(TraceIds.HEADER, "invalid trace id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String responseTraceId = result.getResponse().getHeader(TraceIds.HEADER);
        assertThat(responseTraceId).isNotBlank().isNotEqualTo("invalid trace id");
        assertThat(result.getRequest().getAttribute(TraceIds.REQUEST_ATTRIBUTE))
                .isEqualTo(responseTraceId);
    }

    @Test
    void authenticationAuthorizationRateLimitAndDatabaseErrorsAreStable() throws Exception {
        mockMvc.perform(get("/api/test/errors/credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        mockMvc.perform(get("/api/test/errors/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/test/errors/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "17"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        mockMvc.perform(get("/api/test/errors/database"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATABASE_CONFLICT"))
                .andExpect(content().string(not(containsString("sql-secret-value"))));

        assertThat(meterRegistry.find("flowai.authentication.failures")
                .tag("reason", "credentials")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("flowai.authentication.failures")
                .tag("reason", "credentials")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void unknownExceptionDoesNotExposeInternalMessageOrStackAndRecordsMetrics() throws Exception {
        mockMvc.perform(get("/api/test/errors/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("password=top-secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));

        assertThat(meterRegistry.find("flowai.api.exceptions")
                .tag("code", "INTERNAL_ERROR")
                .tag("status", "500")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("flowai.api.exceptions")
                .tag("code", "INTERNAL_ERROR")
                .tag("status", "500")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("flowai.api.request.duration")
                .tag("method", "GET")
                .tag("outcome", "5xx")
                .timer()).isNotNull();
        assertThat(meterRegistry.find("flowai.api.request.duration")
                .tag("method", "GET")
                .tag("outcome", "5xx")
                .timer().count()).isEqualTo(1L);
    }

    @RestController
    static class ErrorTestController {

        @PostMapping("/api/test/errors/validation")
        void validation(@Valid @RequestBody ValidationBody body) {
        }

        @GetMapping("/api/test/errors/cursor")
        void cursor() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }

        @GetMapping("/api/test/errors/method-validation")
        void methodValidation(@RequestParam @Min(1) int limit) {
        }

        @GetMapping("/api/test/errors/credentials")
        void credentials() {
            throw new BadCredentialsException("password=top-secret");
        }

        @GetMapping("/api/test/errors/denied")
        void denied() {
            throw new AccessDeniedException("internal permission detail");
        }

        @GetMapping("/api/test/errors/rate-limit")
        void rateLimit() {
            throw new RateLimitExceededException(17);
        }

        @GetMapping("/api/test/errors/database")
        void database() {
            throw new DataIntegrityViolationException("sql-secret-value");
        }

        @GetMapping("/api/test/errors/unknown")
        void unknown() {
            throw new IllegalStateException("password=top-secret");
        }
    }

    record ValidationBody(@NotBlank String name) {
    }
}
