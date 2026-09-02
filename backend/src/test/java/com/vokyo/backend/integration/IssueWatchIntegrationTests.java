package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.issue.IssueWatcherRepository;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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
    private IssueWatcherRepository issueWatcherRepository;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMembershipRepository membershipRepository;
    @Autowired
    private JwtService jwtService;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;

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

    @Test
    void watchingFromAnotherWorkspaceIsRejected() throws Exception {
        String token = register("watch-" + uniqueId() + "@example.com");
        String newToken = register("newWatch-" + uniqueId() + "@example.com");
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");
        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + newToken))
            .andExpect(status().isNotFound());
        assertThat(issueWatcherRepository.count()).isZero();
    }

    @Test
    void watchingFromAnotherProjectIsRejected() throws Exception {
        String ownerEmail = "watch-" + uniqueId() + "@example.com";
        String token = register(ownerEmail);
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");

        String outsiderToken = createWorkspaceMember(ownerEmail, "outsider-" + uniqueId() + "@example.com");

        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + outsiderToken))
            .andExpect(status().isNotFound());

        assertThat(issueWatcherRepository.count()).isZero();
    }

    @Test
    void watchingFromDisableUserIsRejected() throws Exception {
        String ownerEmail = "watch-" + uniqueId() + "@example.com";
        String token = register(ownerEmail);
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");
        String outEmail = "outsider-" + uniqueId() + "@example.com";
        String outsiderToken = createWorkspaceMember(ownerEmail, outEmail);
        User outsider = userRepository.findByEmail(outEmail).orElseThrow();
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "userId": "%s", "role": "%s" }
                    """.formatted(outsider.getId(), "MEMBER")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + outsiderToken))
            .andExpect(status().isOk());
        UUID outMemberId = projectMemberRepository
            .findByWorkspace_IdAndProject_IdAndUser_Id(
                workspaceIdOf(ownerEmail),
                UUID.fromString(projectId),
                outsider.getId())
            .orElseThrow()
            .getId();
        mockMvc.perform(delete("/api/projects/" + projectId + "/members/" + outMemberId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + outsiderToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void issueDetailReturnsWatchStatus() throws Exception {
        String ownerEmail = "watch-" + uniqueId() + "@example.com";
        String token = register(ownerEmail);
        String projectId = createProject(token, "Watch Project");
        String issueId = createIssue(token, projectId, "Some issue");
        mockMvc.perform(post("/api/issues/" + issueId + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/issues/" + issueId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watcherCount").value(1))
            .andExpect(jsonPath("$.watched").value(true));
    }

    @Test
    void issueListReturnsWatchStatus() throws Exception {
        String ownerEmail = "watch-" + uniqueId() + "@example.com";
        String token = register(ownerEmail);
        String projectId = createProject(token, "Watch Project");
        String notWatched = createIssue(token, projectId, "notWatched");
        String watched = createIssue(token, projectId, "watched");
        mockMvc.perform(post("/api/issues/" + watched + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/issues").queryParam("projectId", projectId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(watched))
            .andExpect(jsonPath("$.items[0].watched").value(true))
            .andExpect(jsonPath("$.items[1].id").value(notWatched))
            .andExpect(jsonPath("$.items[1].watched").value(false));
    }

    @Test
    void issueBoardReturnsWatchStatus() throws Exception {
        String ownerEmail = "watch-" + uniqueId() + "@example.com";
        String token = register(ownerEmail);
        String projectId = createProject(token, "Watch Project");
        String notWatched = createIssue(token, projectId, "notWatched");
        String watched = createIssue(token, projectId, "watched");
        mockMvc.perform(post("/api/issues/" + watched + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/issues/board").queryParam("projectId", projectId)
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns[0].issues[0].id").value(notWatched))
            .andExpect(jsonPath("$.columns[0].issues[0].watched").value(false))
            .andExpect(jsonPath("$.columns[0].issues[1].id").value(watched))
            .andExpect(jsonPath("$.columns[0].issues[1].watched").value(true));
    }

    @Test
    void issueWatchingFromOtherUser() throws Exception {
        String ownerEmail = "watch-" + uniqueId() + "@example.com";
        String token = register(ownerEmail);
        String projectId = createProject(token, "Watch Project");
        String ownerIssue = createIssue(token, projectId, "notWatched");
        String outEmail = "outsider-" + uniqueId() + "@example.com";
        String outsiderToken = createWorkspaceMember(ownerEmail, outEmail);
        User outsider = userRepository.findByEmail(outEmail).orElseThrow();
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "userId": "%s", "role": "%s" }
                    """.formatted(outsider.getId(), "MEMBER")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/issues/" + ownerIssue + "/watch")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/issues").queryParam("projectId", projectId)
                .header("Authorization", "Bearer " + outsiderToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].watched").value(false));

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

    private String createWorkspaceMember(String ownerEmail, String email) {
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        Workspace workspace = workspaceRepository
            .findFirstByOwner_IdOrderByCreatedAtAsc(owner.getId()).orElseThrow();
        User user = userRepository.save(new User(email, "unused", "Workspace Member"));
        WorkspaceMembership membership = membershipRepository.save(
            new WorkspaceMembership(workspace, user, WorkspaceRole.MEMBER));
        return jwtService.generateAccessToken(user, membership);
    }

    private UUID workspaceIdOf(String ownerEmail) {
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        return workspaceRepository.findFirstByOwner_IdOrderByCreatedAtAsc(owner.getId())
            .orElseThrow().getId();
    }
}
