package com.vokyo.backend.config;

import com.vokyo.backend.ai.AiStatusController;
import com.vokyo.backend.ai.AiProperties;
import com.vokyo.backend.ai.AiStatusService;
import com.vokyo.backend.ai.AiModelGateway;
import com.vokyo.backend.security.ratelimit.RateLimitFilter;
import com.vokyo.backend.web.ApiErrorService;
import com.vokyo.backend.web.ApiErrorWriter;
import com.vokyo.backend.web.ApiObservability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.event.ApplicationEventsTestExecutionListener;
import org.springframework.test.context.event.EventPublishingTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;

import java.time.Duration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AiStatusController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = RateLimitFilter.class
        )
)
@Import({
        SecurityConfig.class,
        ApiErrorWriter.class,
        ApiErrorService.class,
        ApiObservability.class,
        AiStatusControllerTests.TestBeans.class
})
@TestExecutionListeners(
        listeners = {
                ServletTestExecutionListener.class,
                DirtiesContextBeforeModesTestExecutionListener.class,
                ApplicationEventsTestExecutionListener.class,
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class,
                EventPublishingTestExecutionListener.class
        },
        inheritListeners = false
)
class AiStatusControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnauthenticatedStatusRequests() throws Exception {
        mockMvc.perform(get("/api/ai/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void returnsStatusForAuthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/ai/status").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.breakdownAvailable").value(false))
                .andExpect(jsonPath("$.issueSummaryAvailable").value(false))
                .andExpect(jsonPath("$.projectSummaryAvailable").value(false))
                .andExpect(jsonPath("$.agentAvailable").value(false))
                .andExpect(jsonPath("$.disabledReason").value("AI_DISABLED"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        AiStatusService aiStatusService(ObjectProvider<AiModelGateway> gateways) {
            return new AiStatusService(
                    new AiProperties(
                            false,
                            Duration.ofDays(7),
                            Duration.ofSeconds(30),
                            8,
                            20,
                            20,
                            100
                    ),
                    gateways
            );
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException("Token decoding is not used by these MockMvc tests");
            };
        }
    }
}
