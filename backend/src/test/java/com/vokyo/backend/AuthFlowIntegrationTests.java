package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.auth.RefreshToken;
import com.vokyo.backend.auth.RefreshTokenRepository;
import com.vokyo.backend.auth.RefreshTokenService;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AuthFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Autowired
    private JwtService jwtService;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void registerCreatesDefaultWorkspaceAndOwnerMembership() throws Exception {
        JsonNode response = register(
                "MixedCase+" + uniqueId() + "@Example.COM",
                "password123",
                "  Ada Lovelace  ",
                "  FlowAI Team  "
        );

        String accessToken = response.get("accessToken").asText();
        String refreshToken = response.get("refreshToken").asText();
        JsonNode userNode = response.get("user");
        JsonNode workspaceNode = response.get("workspace");

        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(userNode.get("email").asText()).startsWith("mixedcase+").endsWith("@example.com");
        assertThat(userNode.get("displayName").asText()).isEqualTo("Ada Lovelace");
        assertThat(workspaceNode.get("name").asText()).isEqualTo("FlowAI Team");
        assertThat(workspaceNode.get("slug").asText()).isEqualTo("flowai-team");
        assertThat(workspaceNode.get("role").asText()).isEqualTo("OWNER");

        User user = userRepository.findByEmail(userNode.get("email").asText()).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo("password123");
        assertThat(user.getPasswordHash()).startsWith("$2");

        Workspace workspace = workspaceRepository.findFirstByOwner_IdOrderByCreatedAtAsc(user.getId()).orElseThrow();
        assertThat(workspace.getName()).isEqualTo("FlowAI Team");
        assertThat(workspace.getSlug()).isEqualTo("flowai-team");

        WorkspaceMembership membership = membershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.getId(), user.getId())
                .orElseThrow();
        assertThat(membership.getRole()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);

        RefreshToken persistedRefreshToken = refreshTokenRepository
                .findByTokenHash(refreshTokenService.hashToken(refreshToken))
                .orElseThrow();
        assertThat(persistedRefreshToken.getTokenHash()).isNotEqualTo(refreshToken);
        assertThat(refreshTokenRepository.existsByTokenHash(refreshToken)).isFalse();
        assertThat(persistedRefreshToken.isActive()).isTrue();

        assertThat(jwtService.getUserId(accessToken)).isEqualTo(user.getId());
        assertThat(jwtService.getWorkspaceId(accessToken)).isEqualTo(workspace.getId());
        assertThat(jwtService.getMembershipId(accessToken)).isEqualTo(membership.getId());
        assertThat(jwtService.getRole(accessToken)).isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void loginReturnsTokensForExistingUser() throws Exception {
        String email = "login+" + uniqueId() + "@example.com";
        JsonNode registered = register(email, "password123", "Grace Hopper", "Compiler Team");

        JsonNode response = postJson(
                "/api/auth/login",
                """
                {
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(email.toUpperCase())
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.workspace.name").value("Compiler Team"))
                .andExpect(jsonPath("$.workspace.role").value("OWNER"))
                .andReturnJson();

        assertThat(response.get("refreshToken").asText()).isNotEqualTo(registered.get("refreshToken").asText());

        User user = userRepository.findByEmail(email).orElseThrow();
        Workspace workspace = workspaceRepository.findFirstByOwner_IdOrderByCreatedAtAsc(user.getId()).orElseThrow();
        WorkspaceMembership membership = membershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.getId(), user.getId())
                .orElseThrow();

        assertThat(jwtService.getWorkspaceId(response.get("accessToken").asText())).isEqualTo(workspace.getId());
        assertThat(jwtService.getMembershipId(response.get("accessToken").asText())).isEqualTo(membership.getId());
    }

    @Test
    void meRequiresAuthAndReturnsCurrentSession() throws Exception {
        JsonNode registered = register(
                "me+" + uniqueId() + "@example.com",
                "password123",
                "Katherine Johnson",
                "Orbital Mechanics"
        );
        String accessToken = registered.get("accessToken").asText();

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(registered.get("user").get("email").asText()))
                .andExpect(jsonPath("$.user.displayName").value("Katherine Johnson"))
                .andExpect(jsonPath("$.workspace.name").value("Orbital Mechanics"))
                .andExpect(jsonPath("$.workspace.role").value("OWNER"));
    }

    @Test
    void refreshRotatesTokenAndKeepsSessionUsable() throws Exception {
        JsonNode registered = register(
                "refresh+" + uniqueId() + "@example.com",
                "password123",
                "Margaret Hamilton",
                "Apollo Software"
        );
        String oldAccessToken = registered.get("accessToken").asText();
        String oldRefreshToken = registered.get("refreshToken").asText();

        Thread.sleep(1_100L);

        JsonNode refreshed = postJson(
                "/api/auth/refresh",
                """
                {
                  "refreshToken": "%s"
                }
                """.formatted(oldRefreshToken)
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturnJson();

        String newAccessToken = refreshed.get("accessToken").asText();
        String newRefreshToken = refreshed.get("refreshToken").asText();

        assertThat(newAccessToken).isNotEqualTo(oldAccessToken);
        assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken);

        RefreshToken oldPersistedToken = refreshTokenRepository
                .findByTokenHash(refreshTokenService.hashToken(oldRefreshToken))
                .orElseThrow();
        RefreshToken newPersistedToken = refreshTokenRepository
                .findByTokenHash(refreshTokenService.hashToken(newRefreshToken))
                .orElseThrow();
        assertThat(oldPersistedToken.isRevoked()).isTrue();
        assertThat(newPersistedToken.isActive()).isTrue();

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(registered.get("user").get("email").asText()))
                .andExpect(jsonPath("$.workspace.name").value("Apollo Software"));
    }

    @Test
    void workspaceEndpointsUseJwtContext() throws Exception {
        JsonNode registered = register(
                "workspace+" + uniqueId() + "@example.com",
                "password123",
                "Dorothy Vaughan",
                "Analysis Group"
        );
        String accessToken = registered.get("accessToken").asText();
        String userId = registered.get("user").get("id").asText();
        String email = registered.get("user").get("email").asText();
        String workspaceId = registered.get("workspace").get("id").asText();

        mockMvc.perform(get("/api/workspaces/current")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspaceId))
                .andExpect(jsonPath("$.name").value("Analysis Group"))
                .andExpect(jsonPath("$.role").value("OWNER"));

        mockMvc.perform(get("/api/workspaces/current/members")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(userId))
                .andExpect(jsonPath("$[0].email").value(email))
                .andExpect(jsonPath("$[0].displayName").value("Dorothy Vaughan"))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        String email = "duplicate+" + uniqueId() + "@example.com";
        register(email, "password123", "First User", "First Workspace");

        postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Second User",
                  "workspaceName": "Second Workspace"
                }
                """.formatted(email.toUpperCase())
        ).andExpect(status().isConflict());

        assertThat(userRepository.findAll()).hasSize(1);
        assertThat(workspaceRepository.findAll()).hasSize(1);
        assertThat(membershipRepository.findAll()).hasSize(1);
    }

    private JsonNode register(
            String email,
            String password,
            String displayName,
            String workspaceName
    ) throws Exception {
        return postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "%s",
                  "displayName": "%s",
                  "workspaceName": "%s"
                }
                """.formatted(email, password, displayName, workspaceName)
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.workspace.id").isString())
                .andReturnJson();
    }

    private ResultActionsWithJson postJson(String path, String json) throws Exception {
        return new ResultActionsWithJson(mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)));
    }

    private String uniqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private final class ResultActionsWithJson {

        private final org.springframework.test.web.servlet.ResultActions resultActions;

        private ResultActionsWithJson(org.springframework.test.web.servlet.ResultActions resultActions) {
            this.resultActions = resultActions;
        }

        private ResultActionsWithJson andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            resultActions.andExpect(matcher);
            return this;
        }

        private JsonNode andReturnJson() throws Exception {
            MvcResult result = resultActions.andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString());
        }
    }
}
