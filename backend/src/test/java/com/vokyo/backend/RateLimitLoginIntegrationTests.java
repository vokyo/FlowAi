package com.vokyo.backend;

import com.vokyo.backend.security.ratelimit.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy",
        "app.security.rate-limit.enabled=true"
})
class RateLimitLoginIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired
    private RateLimitService rateLimitService;

    @BeforeEach
    void resetRateLimits() {
        rateLimitService.clearAll();
    }

    @Test
    void failedEmailLimitIsNormalizedAndSuccessfulLoginClearsPreviousFailures() throws Exception {
        String email = "rate-limit+" + uniqueId() + "@example.com";
        postJson("/api/auth/register", registerBody(email), null)
                .andExpect(status().isOk());

        for (int attempt = 0; attempt < 3; attempt++) {
            postJson("/api/auth/login", loginBody(email.toUpperCase(), "wrong-password"), null)
                    .andExpect(status().isUnauthorized());
        }

        postJson("/api/auth/login", loginBody(email, "password123"), null)
                .andExpect(status().isOk());

        for (int attempt = 0; attempt < 10; attempt++) {
            postJson("/api/auth/login", loginBody(email.toUpperCase(), "wrong-password"), null)
                    .andExpect(status().isUnauthorized());
        }

        var rejected = postJson("/api/auth/login", loginBody(email, "wrong-password"), null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andReturn();

        assertThat(rejected.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    private String registerBody(String email) {
        return """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Rate Limited User",
                  "workspaceName": "Rate Limit Workspace"
                }
                """.formatted(email);
    }

    private String loginBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }
}
