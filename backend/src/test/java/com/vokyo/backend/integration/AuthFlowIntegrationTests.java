package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.auth.RefreshToken;
import com.vokyo.backend.auth.RefreshTokenCookieService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AuthFlowIntegrationTests extends AbstractMockMvcIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void registerCreatesDefaultWorkspaceAndOwnerMembership() throws Exception {
        AuthSessionResponse registered = register(
                "MixedCase+" + uniqueId() + "@Example.COM",
                "password123",
                "  Ada Lovelace  ",
                "  FlowAI Team  "
        );
        JsonNode response = registered.body();

        String accessToken = response.get("accessToken").asText();
        String refreshToken = registered.refreshToken();
        JsonNode userNode = response.get("user");
        JsonNode workspaceNode = response.get("workspace");

        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(response.has("refreshToken")).isFalse();
        assertThat(registered.setCookieHeader())
                .contains(RefreshTokenCookieService.COOKIE_NAME + "=" + refreshToken)
                .contains("Path=/api")
                .contains("Max-Age=604800")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Secure");
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
    void loginReturnsSessionForExistingUser() throws Exception {
        String email = "login+" + uniqueId() + "@example.com";
        AuthSessionResponse registered = register(email, "password123", "Grace Hopper", "Compiler Team");

        ResultActions loginActions = postJson(
                "/api/auth/login",
                """
                {
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(email.toUpperCase()),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.workspace.name").value("Compiler Team"))
                .andExpect(jsonPath("$.workspace.role").value("OWNER"));
        JsonNode response = readJson(loginActions);
        String loginRefreshToken = refreshToken(loginActions);

        assertThat(loginRefreshToken).isNotEqualTo(registered.refreshToken());

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
        AuthSessionResponse registered = register(
                "me+" + uniqueId() + "@example.com",
                "password123",
                "Katherine Johnson",
                "Orbital Mechanics"
        );
        JsonNode response = registered.body();
        String accessToken = response.get("accessToken").asText();

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(response.get("user").get("email").asText()))
                .andExpect(jsonPath("$.user.displayName").value("Katherine Johnson"))
                .andExpect(jsonPath("$.workspace.name").value("Orbital Mechanics"))
                .andExpect(jsonPath("$.workspace.role").value("OWNER"));
    }

    @Test
    void refreshRotatesTokenAndKeepsSessionUsable() throws Exception {
        AuthSessionResponse registered = register(
                "refresh+" + uniqueId() + "@example.com",
                "password123",
                "Margaret Hamilton",
                "Apollo Software"
        );
        String oldAccessToken = registered.body().get("accessToken").asText();
        String oldRefreshToken = registered.refreshToken();

        Thread.sleep(1_100L);

        ResultActions refreshActions = postJson(
                "/api/auth/refresh",
                "{}",
                null,
                oldRefreshToken
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        JsonNode refreshed = readJson(refreshActions);

        String newAccessToken = refreshed.get("accessToken").asText();
        String newRefreshToken = refreshToken(refreshActions);

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
                .andExpect(jsonPath("$.user.email").value(registered.body().get("user").get("email").asText()))
                .andExpect(jsonPath("$.workspace.name").value("Apollo Software"));
    }

    @Test
    void replayingARotatedRefreshTokenRevokesEverySessionForThatMembership() throws Exception {
        String email = "reuse+" + uniqueId() + "@example.com";
        AuthSessionResponse registered = register(email, "password123", "Reuse User", "Reuse Workspace");
        String stolenRefreshToken = registered.refreshToken();

        // Whoever copied the cookie gets there first and rotates it into a token of
        // their own, leaving the real user holding one the server has already retired.
        String attackerRefreshToken = refreshToken(
                postJson("/api/auth/refresh", "{}", null, stolenRefreshToken)
                        .andExpect(status().isOk())
        );
        String secondDeviceRefreshToken = login(email, "password123");

        // Far enough past the rotation that concurrent tabs cannot explain the replay.
        backdateRevocation(stolenRefreshToken, Duration.ofHours(1));

        postJson("/api/auth/refresh", "{}", null, stolenRefreshToken)
                .andExpect(status().isUnauthorized());

        // The rejection alone would leave the attacker's rotated token alive, so the
        // whole membership is signed out — the second device included.
        assertThat(persistedRefreshToken(attackerRefreshToken).isRevoked()).isTrue();
        assertThat(persistedRefreshToken(secondDeviceRefreshToken).isRevoked()).isTrue();
        postJson("/api/auth/refresh", "{}", null, attackerRefreshToken)
                .andExpect(status().isUnauthorized());
        postJson("/api/auth/refresh", "{}", null, secondDeviceRefreshToken)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayInsideTheGraceWindowRejectsWithoutRevokingTheRotatedSession() throws Exception {
        String email = "grace+" + uniqueId() + "@example.com";
        AuthSessionResponse registered = register(email, "password123", "Grace User", "Grace Workspace");

        String rotatedRefreshToken = refreshToken(
                postJson("/api/auth/refresh", "{}", null, registered.refreshToken())
                        .andExpect(status().isOk())
        );

        // Tabs restoring together all send the pre-rotation cookie. The losers are
        // rejected, but that is concurrency rather than theft, so nothing is revoked.
        postJson("/api/auth/refresh", "{}", null, registered.refreshToken())
                .andExpect(status().isUnauthorized());

        assertThat(persistedRefreshToken(rotatedRefreshToken).isActive()).isTrue();
        postJson("/api/auth/refresh", "{}", null, rotatedRefreshToken)
                .andExpect(status().isOk());
    }

    @Test
    void cookieAuthenticatedWritesAcceptSameOriginAndRejectCrossSiteRequests() throws Exception {
        AuthSessionResponse registered = register(
                "origin+" + uniqueId() + "@example.com",
                "password123",
                "Origin User",
                "Origin Workspace"
        );

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie(registered.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.ORIGIN, "https://attacker.example"))
                .andExpect(status().isForbidden());

        ResultActions sameOriginRefresh = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie(registered.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        String rotatedRefreshToken = refreshToken(sameOriginRefresh);

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(refreshCookie(rotatedRefreshToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());

        postJson("/api/auth/refresh", "{}", null, rotatedRefreshToken)
                .andExpect(status().isOk());
    }

    @Test
    void workspaceEndpointsUseJwtContext() throws Exception {
        AuthSessionResponse registered = register(
                "workspace+" + uniqueId() + "@example.com",
                "password123",
                "Dorothy Vaughan",
                "Analysis Group"
        );
        JsonNode response = registered.body();
        String accessToken = response.get("accessToken").asText();
        String userId = response.get("user").get("id").asText();
        String email = response.get("user").get("email").asText();
        String workspaceId = response.get("workspace").get("id").asText();

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
                """.formatted(email.toUpperCase()),
                null
        ).andExpect(status().isConflict());

        assertThat(userRepository.findAll()).hasSize(1);
        assertThat(workspaceRepository.findAll()).hasSize(1);
        assertThat(membershipRepository.findAll()).hasSize(1);
    }

    private AuthSessionResponse register(
            String email,
            String password,
            String displayName,
            String workspaceName
    ) throws Exception {
        ResultActions registerActions = postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "%s",
                  "displayName": "%s",
                  "workspaceName": "%s"
                }
                """.formatted(email, password, displayName, workspaceName),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.workspace.id").isString());
        return new AuthSessionResponse(
                readJson(registerActions),
                refreshToken(registerActions),
                registerActions.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE)
        );
    }

    private String login(String email, String password) throws Exception {
        return refreshToken(postJson(
                "/api/auth/login",
                """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password),
                null
        ).andExpect(status().isOk()));
    }

    private RefreshToken persistedRefreshToken(String plainRefreshToken) {
        return refreshTokenRepository
                .findByTokenHash(refreshTokenService.hashToken(plainRefreshToken))
                .orElseThrow();
    }

    /**
     * Ages a revocation so the replay lands outside the grace window. Cheaper and far
     * steadier than sleeping through it, and it keeps the shipped grace in the test
     * profile rather than tuning the window until the assertion passes.
     */
    private void backdateRevocation(String plainRefreshToken, Duration age) {
        int updated = jdbcTemplate.update(
                "update refresh_tokens set revoked_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minus(age)),
                refreshTokenService.hashToken(plainRefreshToken)
        );
        assertThat(updated).isEqualTo(1);
    }

    private record AuthSessionResponse(
            JsonNode body,
            String refreshToken,
            String setCookieHeader
    ) {
    }
}
