package com.vokyo.backend.config;

import com.vokyo.backend.ai.breakdown.IssueBreakdownController;
import com.vokyo.backend.ai.breakdown.IssueBreakdownResult;
import com.vokyo.backend.ai.breakdown.IssueBreakdownService;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownRequest;
import com.vokyo.backend.ai.breakdown.dto.IssueBreakdownSuggestionResponse;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.IssuePriority;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = IssueBreakdownController.class,
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
        IssueBreakdownControllerTests.TestBeans.class
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
class IssueBreakdownControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestBreakdownService breakdownService;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/ai/issues/{issueId}/breakdown", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void validatesTheRequestBeforeCallingTheService() throws Exception {
        mockMvc.perform(post("/api/ai/issues/{issueId}/breakdown", UUID.randomUUID())
                        .with(jwt())
                        .contentType("application/json")
                        .content("{\"maxItems\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsTheDraftSuggestionContract() throws Exception {
        UUID issueId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID suggestionId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-21T01:00:00Z");
        IssueBreakdownResult content = new IssueBreakdownResult(
                "Delivery plan",
                List.of(
                        item("item-1", List.of()),
                        item("item-2", List.of("item-1"))
                ),
                List.of()
        );
        breakdownService.response = new IssueBreakdownSuggestionResponse(
                        suggestionId,
                        AiSuggestionType.ISSUE_BREAKDOWN,
                        AiSuggestionStatus.DRAFT,
                        projectId,
                        issueId,
                        content,
                        new IssueBreakdownSuggestionResponse.Metadata(
                                "issue-breakdown-v1", createdAt, false
                        ),
                        createdAt,
                        createdAt.plusSeconds(604_800)
                );

        mockMvc.perform(post("/api/ai/issues/{issueId}/breakdown", issueId)
                        .with(jwt())
                        .contentType("application/json")
                        .content("{\"instruction\":\"MVP only\",\"maxItems\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(suggestionId.toString()))
                .andExpect(jsonPath("$.type").value("ISSUE_BREAKDOWN"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.sourceIssueId").value(issueId.toString()))
                .andExpect(jsonPath("$.content.overview").value("Delivery plan"))
                .andExpect(jsonPath("$.content.items[1].dependsOnClientItemIds[0]").value("item-1"))
                .andExpect(jsonPath("$.metadata.promptVersion").value("issue-breakdown-v1"))
                .andExpect(jsonPath("$.metadata.contextTruncated").value(false));
    }

    private IssueBreakdownResult.Item item(String id, List<String> dependencies) {
        return new IssueBreakdownResult.Item(
                id,
                "Task " + id,
                "Description",
                IssuePriority.HIGH,
                List.of("It works"),
                List.of(),
                null,
                null,
                dependencies
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TestBreakdownService breakdownService() {
            return new TestBreakdownService();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException("Token decoding is not used by these MockMvc tests");
            };
        }
    }

    static final class TestBreakdownService extends IssueBreakdownService {
        private IssueBreakdownSuggestionResponse response;

        private TestBreakdownService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public IssueBreakdownSuggestionResponse generate(
                org.springframework.security.oauth2.jwt.Jwt jwt,
                UUID issueId,
                IssueBreakdownRequest request
        ) {
            return response;
        }
    }
}
