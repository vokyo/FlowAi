package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.auth.RefreshTokenService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ending a session ends the access tokens issued before it at once, not when they
 * expire: every way a session ends raises the user's token version, and a token
 * carrying an older one is refused on its next request.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AccessTokenRevocationIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private AgentTokenService agentTokenService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void aLoggedOutAccessTokenStopsWorkingAtOnce() throws Exception {
        Session session = register("logout");
        assertWorks(session.accessToken());

        logout(session.refreshToken());

        assertRefused(session.accessToken());
        assertWorks(login(session.email()).accessToken());
    }

    @Test
    void loggingOutOnOneDeviceMakesTheOtherDevicesRefreshOnce() throws Exception {
        Session laptop = register("two-devices");
        Session phone = login(laptop.email());

        logout(laptop.refreshToken());

        assertRefused(phone.accessToken());
        Session refreshedPhone = refresh(phone.refreshToken());
        assertWorks(refreshedPhone.accessToken());
    }

    @Test
    void changingThePasswordEndsEveryAccessToken() throws Exception {
        Session session = register("password");
        Session otherDevice = login(session.email());

        mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(session.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "password123", "newPassword": "new-password-123" }
                                """))
                .andExpect(status().isNoContent());

        assertRefused(session.accessToken());
        assertRefused(otherDevice.accessToken());
    }

    @Test
    void signingOutEverywhereEndsEveryAccessTokenAndEverySession() throws Exception {
        Session session = register("everywhere");
        Session otherDevice = login(session.email());

        mockMvc.perform(delete("/api/me/sessions")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isNoContent());

        assertRefused(session.accessToken());
        assertRefused(otherDevice.accessToken());
        postJson("/api/auth/refresh", "{}", null, otherDevice.refreshToken())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aReplayedRefreshTokenEndsTheAccessTokenWhoeverReplayedItHolds() throws Exception {
        Session victim = register("replay");
        String stolenRefreshToken = victim.refreshToken();
        Session attacker = refresh(stolenRefreshToken);
        assertWorks(attacker.accessToken());

        // Far enough past the rotation that concurrent tabs cannot explain the replay.
        backdateRevocation(stolenRefreshToken, Duration.ofHours(1));
        postJson("/api/auth/refresh", "{}", null, stolenRefreshToken)
                .andExpect(status().isUnauthorized());

        assertRefused(attacker.accessToken());
    }

    @Test
    void aLogoutDoesNotEndTheAgentTokenOfARunAlreadyUnderWay() throws Exception {
        Session session = register("agent-run");
        String projectId = readJson(postJson(
                "/api/projects",
                """
                { "name": "Running project" }
                """,
                session.accessToken()
        ).andExpect(status().isOk())).get("id").asText();
        String agentToken = agentTokenService.issue(
                context(session.email()), UUID.fromString(projectId), UUID.randomUUID());

        logout(session.refreshToken());

        mockMvc.perform(get("/api/internal/agent/project/members").header("Authorization", bearer(agentToken)))
                .andExpect(status().isOk());
    }

    /**
     * Saving a user writes every column it maps, so a raise made after the user was
     * loaded would be written back over if the entity mapped the version as writable.
     */
    @Test
    void savingAUserLoadedBeforeARaiseKeepsTheRaise() throws Exception {
        Session session = register("stale-save");
        UUID userId = userRepository.findByEmail(session.email()).orElseThrow().getId();
        TransactionTemplate meanwhile = new TransactionTemplate(transactionManager);
        meanwhile.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User loadedBefore = userRepository.findById(userId).orElseThrow();
            meanwhile.executeWithoutResult(inner -> userRepository.revokeAccessTokens(userId));
            loadedBefore.changeDisplayName("Renamed meanwhile");
        });

        assertThat(userRepository.findTokenVersionById(userId)).contains(1);
        assertRefused(session.accessToken());
    }

    private void assertWorks(String accessToken) throws Exception {
        currentWorkspace(accessToken).andExpect(status().isOk());
    }

    private void assertRefused(String accessToken) throws Exception {
        currentWorkspace(accessToken).andExpect(status().isUnauthorized());
    }

    private ResultActions currentWorkspace(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/workspaces/current").header("Authorization", bearer(accessToken)));
    }

    private Session register(String prefix) throws Exception {
        String email = prefix + "+" + uniqueId() + "@example.com";
        return session(email, postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Revocation User",
                  "workspaceName": "Revocation Workspace"
                }
                """.formatted(email),
                null
        ).andExpect(status().isOk()));
    }

    private Session login(String email) throws Exception {
        return session(email, postJson(
                "/api/auth/login",
                """
                { "email": "%s", "password": "password123" }
                """.formatted(email),
                null
        ).andExpect(status().isOk()));
    }

    private Session refresh(String refreshToken) throws Exception {
        return session(null, postJson("/api/auth/refresh", "{}", null, refreshToken).andExpect(status().isOk()));
    }

    private void logout(String refreshToken) throws Exception {
        postJson("/api/auth/logout", "{}", null, refreshToken).andExpect(status().isNoContent());
    }

    private Session session(String email, ResultActions actions) throws Exception {
        JsonNode body = readJson(actions);
        return new Session(email, body.get("accessToken").asText(), refreshToken(actions));
    }

    private CurrentWorkspaceContext context(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        WorkspaceMembership membership = membershipRepository
                .findByUser_IdAndStatusOrderByLastAccessedAtDescJoinedAtAsc(
                        user.getId(), MembershipStatus.ACTIVE)
                .getFirst();
        Workspace workspace = workspaceRepository.findById(membership.getWorkspace().getId()).orElseThrow();
        return new CurrentWorkspaceContext(user, workspace, membership);
    }

    private void backdateRevocation(String plainRefreshToken, Duration age) {
        int updated = jdbcTemplate.update(
                "update refresh_tokens set revoked_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minus(age)),
                refreshTokenService.hashToken(plainRefreshToken)
        );
        assertThat(updated).isEqualTo(1);
    }

    private record Session(String email, String accessToken, String refreshToken) {
    }
}
