package com.vokyo.backend.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.vokyo.backend.ai.suggestion.AiSuggestionApplicationService;
import com.vokyo.backend.ai.suggestion.AiSuggestionController;
import com.vokyo.backend.ai.suggestion.AiSuggestionApplyService;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.ai.suggestion.dto.AiSuggestionResponse;
import com.vokyo.backend.ai.suggestion.dto.ApplyIssueBreakdownRequest;
import com.vokyo.backend.ai.suggestion.dto.ApplySuggestionResponse;
import com.vokyo.backend.security.ratelimit.RateLimitFilter;
import com.vokyo.backend.web.ApiErrorService;
import com.vokyo.backend.web.ApiErrorWriter;
import com.vokyo.backend.web.ApiObservability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.event.ApplicationEventsTestExecutionListener;
import org.springframework.test.context.event.EventPublishingTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AiSuggestionController.class,
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
        AiSuggestionControllerTests.TestBeans.class
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
class AiSuggestionControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestSuggestionApplicationService suggestionService;

    @Autowired
    private TestSuggestionApplyService applyService;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(
                        "/api/ai/suggestions/{suggestionId}",
                        UUID.randomUUID()
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void returnsOwnedSuggestionWithoutPrivateInputHash() throws Exception {
        UUID suggestionId = UUID.randomUUID();
        suggestionService.response = response(
                suggestionId,
                AiSuggestionStatus.DRAFT,
                null
        );

        mockMvc.perform(get(
                        "/api/ai/suggestions/{suggestionId}",
                        suggestionId
                ).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(suggestionId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.content.overview").value("Plan"))
                .andExpect(jsonPath("$.metadata.contextTruncated").value(true))
                .andExpect(jsonPath("$.metadata.model").value("fake-model"))
                .andExpect(jsonPath("$.createdIssueIds").isArray())
                .andExpect(jsonPath("$.inputHash").doesNotExist());
    }

    @Test
    void dismissesDraftSuggestion() throws Exception {
        UUID suggestionId = UUID.randomUUID();
        Instant dismissedAt = Instant.parse("2026-07-21T02:00:00Z");
        suggestionService.response = response(
                suggestionId,
                AiSuggestionStatus.DISMISSED,
                dismissedAt
        );

        mockMvc.perform(post(
                        "/api/ai/suggestions/{suggestionId}/dismiss",
                        suggestionId
                ).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.dismissedAt").value(dismissedAt.toString()));
    }

    @Test
    void validatesAndAppliesSelectedItems() throws Exception {
        UUID suggestionId = UUID.randomUUID();
        UUID createdIssueId = UUID.randomUUID();
        Instant appliedAt = Instant.parse("2026-07-21T03:00:00Z");
        applyService.response = new ApplySuggestionResponse(
                suggestionId,
                AiSuggestionStatus.APPLIED,
                List.of(createdIssueId),
                appliedAt
        );

        mockMvc.perform(post(
                        "/api/ai/suggestions/{suggestionId}/apply",
                        suggestionId
                ).with(jwt())
                        .contentType("application/json")
                        .content("""
                                {
                                  "idempotencyKey": "%s",
                                  "items": [{
                                    "clientItemId": "item-1",
                                    "selected": true,
                                    "title": "Created task",
                                    "description": "Description",
                                    "priority": "HIGH",
                                    "labelIds": []
                                  }]
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.createdIssueIds[0]")
                        .value(createdIssueId.toString()))
                .andExpect(jsonPath("$.appliedAt").value(appliedAt.toString()));
    }

    private AiSuggestionResponse response(
            UUID suggestionId,
            AiSuggestionStatus status,
            Instant dismissedAt
    ) {
        Instant createdAt = Instant.parse("2026-07-21T01:00:00Z");
        return new AiSuggestionResponse(
                suggestionId,
                AiSuggestionType.ISSUE_BREAKDOWN,
                status,
                UUID.randomUUID(),
                UUID.randomUUID(),
                JsonNodeFactory.instance.objectNode().put("overview", "Plan"),
                new AiSuggestionResponse.Metadata(
                        "issue-breakdown-v1",
                        createdAt,
                        true,
                        "fake",
                        "fake-model",
                        10,
                        20
                ),
                List.of(),
                createdAt,
                createdAt.plusSeconds(604_800),
                null,
                dismissedAt
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TestSuggestionApplicationService suggestionApplicationService() {
            return new TestSuggestionApplicationService();
        }

        @Bean
        TestSuggestionApplyService suggestionApplyService() {
            return new TestSuggestionApplyService();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException(
                        "Token decoding is not used by these MockMvc tests"
                );
            };
        }
    }

    static final class TestSuggestionApplicationService
            extends AiSuggestionApplicationService {

        private AiSuggestionResponse response;

        private TestSuggestionApplicationService() {
            super(null, null, null, null);
        }

        @Override
        public AiSuggestionResponse get(Jwt jwt, UUID suggestionId) {
            return response;
        }

        @Override
        public AiSuggestionResponse dismiss(Jwt jwt, UUID suggestionId) {
            return response;
        }
    }

    static final class TestSuggestionApplyService
            extends AiSuggestionApplyService {

        private ApplySuggestionResponse response;

        private TestSuggestionApplyService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public ApplySuggestionResponse apply(
                Jwt jwt,
                UUID suggestionId,
                ApplyIssueBreakdownRequest request
        ) {
            return response;
        }
    }
}
