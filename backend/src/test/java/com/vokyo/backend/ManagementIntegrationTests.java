package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.auth.RefreshTokenRepository;
import com.vokyo.backend.auth.RefreshTokenService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class ManagementIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Test
    void managesProjectLabelsWorkflowMigrationArchivingAndDeletion() throws Exception {
        Session owner = register("manage-project");
        JsonNode project = readJson(postJson(
                "/api/projects",
                """
                { "name": "Original", "description": "Before" }
                """,
                owner.accessToken()
        ).andExpect(status().isOk()));
        String projectId = project.get("id").asText();

        mockMvc.perform(patch("/api/projects/{id}", projectId)
                        .header("Authorization", bearer(owner.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Renamed", "description": "After" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.description").value("After"));

        JsonNode label = readJson(postJson(
                "/api/projects/%s/labels".formatted(projectId),
                """
                { "name": "Backend", "color": "#112233" }
                """,
                owner.accessToken()
        ).andExpect(status().isOk()));
        String labelId = label.get("id").asText();

        mockMvc.perform(patch("/api/projects/{projectId}/labels/{labelId}", projectId, labelId)
                        .header("Authorization", bearer(owner.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "API", "color": "#445566" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("API"))
                .andExpect(jsonPath("$.color").value("#445566"));

        JsonNode states = objectMapper.readTree(mockMvc.perform(
                        get("/api/projects/{projectId}/workflow-states", projectId)
                                .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String replacementId = states.get(0).get("id").asText();
        JsonNode reviewState = readJson(postJson(
                "/api/projects/%s/workflow-states".formatted(projectId),
                """
                { "name": "Review", "category": "IN_PROGRESS" }
                """,
                owner.accessToken()
        ).andExpect(status().isOk()));

        JsonNode issue = readJson(postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Migrate me",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, reviewState.get("id").asText()),
                owner.accessToken()
        ).andExpect(status().isOk()));

        mockMvc.perform(delete(
                        "/api/projects/{projectId}/workflow-states/{stateId}",
                        projectId,
                        reviewState.get("id").asText()
                )
                        .header("Authorization", bearer(owner.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "replacementWorkflowStateId": "%s" }
                                """.formatted(replacementId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/issues/{issueId}", issue.get("id").asText())
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowState.id").value(replacementId));

        mockMvc.perform(delete("/api/projects/{projectId}/labels/{labelId}", projectId, labelId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());

        postJson("/api/projects/%s/archive".formatted(projectId), "{}", owner.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isString());
        mockMvc.perform(get("/api/projects").header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        postJson("/api/projects/%s/restore".formatted(projectId), "{}", owner.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        mockMvc.perform(delete("/api/projects/{projectId}", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/projects/{projectId}", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesAccountChangesPasswordAndRevokesSessions() throws Exception {
        Session session = register("account");

        mockMvc.perform(patch("/api/me/profile")
                        .header("Authorization", bearer(session.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "displayName": "Updated name" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated name"));

        mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(session.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "password123", "newPassword": "new-password-123" }
                                """))
                .andExpect(status().isNoContent());

        postJson("/api/auth/refresh", "{}", null, session.refreshToken())
                .andExpect(status().isUnauthorized());
        postJson("/api/auth/login", loginBody(session.email(), "password123"), null)
                .andExpect(status().isUnauthorized());
        var loginActions = postJson(
                "/api/auth/login",
                loginBody(session.email(), "new-password-123"),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        JsonNode loggedIn = readJson(loginActions);
        String loggedInRefreshToken = refreshToken(loginActions);

        postJson("/api/auth/logout", "{}", null, loggedInRefreshToken)
                .andExpect(status().isNoContent());
        postJson("/api/auth/refresh", "{}", null, loggedInRefreshToken)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerChangesAndDisablesWorkspaceMemberAndRevokesMembershipSessions() throws Exception {
        Session owner = register("workspace-owner");
        Session memberHome = register("workspace-member");
        Workspace workspace = workspaceRepository.findById(UUID.fromString(owner.workspaceId())).orElseThrow();
        User memberUser = userRepository.findByEmail(memberHome.email()).orElseThrow();
        WorkspaceMembership member = membershipRepository.saveAndFlush(
                new WorkspaceMembership(workspace, memberUser, WorkspaceRole.MEMBER)
        );
        String memberRefreshToken = refreshTokenService.createRefreshToken(memberUser, member);

        mockMvc.perform(patch("/api/workspaces/current/members/{memberId}", member.getId())
                        .header("Authorization", bearer(owner.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "role": "ADMIN" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(delete("/api/workspaces/current/members/{memberId}", member.getId())
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());

        WorkspaceMembership disabled = membershipRepository.findById(member.getId()).orElseThrow();
        assertThat(disabled.getStatus().name()).isEqualTo("DISABLED");
        assertThat(refreshTokenRepository.findByTokenHash(refreshTokenService.hashToken(memberRefreshToken))
                .orElseThrow().isRevoked()).isTrue();
    }

    private Session register(String prefix) throws Exception {
        String email = prefix + "+" + uniqueId() + "@example.com";
        var registerActions = postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Management User",
                  "workspaceName": "Management Workspace"
                }
                """.formatted(email),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        JsonNode response = readJson(registerActions);
        return new Session(
                email,
                response.get("accessToken").asText(),
                refreshToken(registerActions),
                response.get("workspace").get("id").asText()
        );
    }

    private String loginBody(String email, String password) {
        return """
                { "email": "%s", "password": "%s" }
                """.formatted(email, password);
    }

    private record Session(String email, String accessToken, String refreshToken, String workspaceId) {
    }
}
