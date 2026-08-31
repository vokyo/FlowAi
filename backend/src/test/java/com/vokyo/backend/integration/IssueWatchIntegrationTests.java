package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class IssueWatchIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void watchesAnIssue() throws Exception {
        String token = register("watch-" + uniqueId() + "@example.com");
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");

        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watched").value(true))
            .andExpect(jsonPath("$.watcherCount").value(1));

    }

    @Test
    void watchingTwiceIsIdempotent() throws Exception {
        String token = register("watch-" + uniqueId() + "@example.com");
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");
        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watcherCount").value(1));
    }

    @Test
    void unwatchAnIssue() throws Exception {
        String token = register("watch-" + uniqueId() + "@example.com");
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");
        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watched").value(false))
            .andExpect(jsonPath("$.watcherCount").value(0));
    }

    @Test
    void unwatchingTwiceIsIdempotent() throws Exception {
        String token = register("watch-" + uniqueId() + "@example.com");
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");

        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watcherCount").value(0));
    }


    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "password123",
                      "displayName": "Watch Tester",
                      "workspaceName": "Watch Workspace"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private String createProject(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "name": "%s" }
                    """.formatted(name)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createIssue(String token, String projectId, String title) throws Exception {
        String body = mockMvc.perform(post("/api/issues")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": "%s",
                      "title": "%s"
                    }
                    """.formatted(projectId, title)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String uniqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
