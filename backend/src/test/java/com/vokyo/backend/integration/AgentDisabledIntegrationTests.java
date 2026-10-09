package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.integration.AgentRunIntegrationTests.AgentStandIn;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deployment that does not run the planning agent says so, refuses to start or
 * revise a run without trying to reach the agent, and still lets people read the
 * runs they have.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {"spring.ai.openai.api-key=dummy", "app.agent.enabled=false"})
class AgentDisabledIntegrationTests extends AbstractMockMvcIntegrationTest {

    private static final AgentStandIn AGENT = AgentStandIn.start();

    @DynamicPropertySource
    static void pointTheBackendAtTheStandIn(DynamicPropertyRegistry registry) {
        registry.add("app.agent.base-url", AGENT::baseUrl);
    }

    @AfterAll
    static void stopTheStandIn() {
        AGENT.stop();
    }

    @Test
    void theAgentIsReportedOffAndRunsAreRefusedWithoutCallingIt() throws Exception {
        String accessToken = register();
        String projectId = readJson(postJson("/api/projects", """
                {"name": "Agentless project"}
                """, accessToken).andExpect(status().isOk())).get("id").asText();

        mockMvc.perform(get("/api/ai/status").header("Authorization", bearer(accessToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentAvailable").value(false));
        postJson("/api/agent/runs", """
                {"projectId": "%s", "goal": "Plan the login work"}
                """.formatted(projectId), accessToken)
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AI_AGENT_UNAVAILABLE"));
        postJson("/api/agent/runs/" + UUID.randomUUID() + "/revisions", """
                {"basedOnVersion": 1, "feedback": "Shorter"}
                """, accessToken)
            .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/agent/runs").param("projectId", projectId)
                .header("Authorization", bearer(accessToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        assertThat(AGENT.received()).isEmpty();
    }

    private String register() throws Exception {
        JsonNode registered = readJson(postJson("/api/auth/register", """
                {"email": "agentless-%s@example.com", "password": "password123",
                 "displayName": "Agentless", "workspaceName": "Agentless workspace"}
                """.formatted(UUID.randomUUID()), null).andExpect(status().isOk()));
        return registered.get("accessToken").asText();
    }
}
