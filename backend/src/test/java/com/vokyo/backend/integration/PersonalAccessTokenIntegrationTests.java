package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.accesstoken.AccessTokenPrincipal;
import com.vokyo.backend.accesstoken.PersonalAccessToken;
import com.vokyo.backend.accesstoken.PersonalAccessTokenRepository;
import com.vokyo.backend.accesstoken.PersonalAccessTokenService;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Personal access tokens from settings to the check the MCP endpoint will make: a
 * token is shown once and kept as a hash, speaks for one user in one workspace,
 * expires, and ends when it is revoked, when the membership is disabled, or when the
 * password changes or the user signs out everywhere, but not on a plain logout.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class PersonalAccessTokenIntegrationTests extends AbstractMockMvcIntegrationTest {

    private static final String TOKENS = "/api/me/access-tokens";

    @Autowired private PersonalAccessTokenService accessTokens;
    @Autowired private PersonalAccessTokenRepository tokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void aTokenIsShownOnceKeptAsAHashAndListedWithoutIt() throws Exception {
        Session session = register("create");

        JsonNode created = readJson(create(session, "Claude Code on my laptop", 90)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Claude Code on my laptop")));
        String token = created.get("token").asText();
        assertThat(token).startsWith("flowai_pat_");
        assertThat(Duration.between(Instant.parse(created.get("createdAt").asText()),
                Instant.parse(created.get("expiresAt").asText()))).isEqualTo(Duration.ofDays(90));

        mockMvc.perform(get(TOKENS).header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Claude Code on my laptop"))
                .andExpect(jsonPath("$[0].expired").value(false))
                .andExpect(jsonPath("$[0].lastUsedAt").doesNotExist())
                .andExpect(jsonPath("$[0].token").doesNotExist());
        assertThat(jdbcTemplate.queryForList("select token_hash from personal_access_tokens", String.class))
                .singleElement()
                .isNotEqualTo(token);
    }

    @Test
    void aTokenLastsThirtyNinetyOrThreeHundredSixtyFiveDaysAndNeedsAName() throws Exception {
        Session session = register("lifetime");

        create(session, "Forever", 3650).andExpect(status().isBadRequest());
        create(session, "  ", 30).andExpect(status().isBadRequest());
        create(session, "x".repeat(101), 30).andExpect(status().isBadRequest());
        create(session, "A month", 30).andExpect(status().isOk());
        create(session, "A year", 365).andExpect(status().isOk());
    }

    @Test
    void aUserKeepsAtMostTenActiveTokens() throws Exception {
        Session session = register("limit");
        for (int index = 1; index <= 10; index++) {
            create(session, "Token " + index, 30).andExpect(status().isOk());
        }

        create(session, "Token 11", 30)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You can keep at most 10 access tokens; revoke one first"));

        revoke(session, firstListedId(session)).andExpect(status().isNoContent());
        create(session, "Token 11", 30).andExpect(status().isOk());
    }

    @Test
    void onlyItsOwnerCanRevokeATokenAndRevokingAgainIsHarmless() throws Exception {
        Session owner = register("revoke-owner");
        Session stranger = register("revoke-stranger");
        String token = createToken(owner, "Cursor");
        String id = firstListedId(owner);

        revoke(stranger, id).andExpect(status().isNotFound());
        assertThat(accessTokens.authenticate(token)).isPresent();

        revoke(owner, id).andExpect(status().isNoContent());
        revoke(owner, id).andExpect(status().isNoContent());
        assertThat(accessTokens.authenticate(token)).isEmpty();
        mockMvc.perform(get(TOKENS).header("Authorization", bearer(owner.accessToken())))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aTokenSpeaksForItsUserInItsWorkspaceAndRecordsItsUse() throws Exception {
        Session session = register("authenticate");
        String token = createToken(session, "Claude Code");

        Optional<AccessTokenPrincipal> principal = accessTokens.authenticate(token);

        assertThat(principal).isPresent();
        assertThat(principal.get().workspaceId().toString()).isEqualTo(session.workspaceId());
        PersonalAccessToken stored = tokenRepository.findById(principal.get().tokenId()).orElseThrow();
        assertThat(stored.getLastUsedAt()).isNotNull();
    }

    @Test
    void malformedUnknownAndExpiredTokensSpeakForNoOne() throws Exception {
        Session session = register("refused");
        String token = createToken(session, "Expiring");

        assertThat(accessTokens.authenticate(null)).isEmpty();
        assertThat(accessTokens.authenticate("not-a-flowai-token")).isEmpty();
        assertThat(accessTokens.authenticate("flowai_pat_" + "x".repeat(43))).isEmpty();

        jdbcTemplate.update("update personal_access_tokens set created_at = ?, expires_at = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(31))),
                Timestamp.from(Instant.now().minusSeconds(1)));
        assertThat(accessTokens.authenticate(token)).isEmpty();
        mockMvc.perform(get(TOKENS).header("Authorization", bearer(session.accessToken())))
                .andExpect(jsonPath("$[0].expired").value(true));
    }

    @Test
    void aDisabledMembershipEndsItsTokens() throws Exception {
        Session session = register("disabled");
        String token = createToken(session, "Claude Code");

        jdbcTemplate.update("update workspace_memberships set status = 'DISABLED' where workspace_id = ?",
                UUID.fromString(session.workspaceId()));

        assertThat(accessTokens.authenticate(token)).isEmpty();
    }

    @Test
    void changingThePasswordRevokesEveryToken() throws Exception {
        Session session = register("password");
        String first = createToken(session, "Laptop");
        String second = createToken(session, "Desktop");

        mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(session.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "currentPassword": "password123", "newPassword": "new-password-123" }
                                """))
                .andExpect(status().isNoContent());

        assertThat(accessTokens.authenticate(first)).isEmpty();
        assertThat(accessTokens.authenticate(second)).isEmpty();
    }

    @Test
    void signingOutEverywhereRevokesEveryTokenButAPlainLogoutDoesNot() throws Exception {
        Session session = register("sign-out");
        String token = createToken(session, "Claude Code");

        postJson("/api/auth/logout", "{}", null, session.refreshToken()).andExpect(status().isNoContent());
        assertThat(accessTokens.authenticate(token)).as("a logout leaves AI apps alone").isPresent();

        Session again = login(session.email());
        mockMvc.perform(delete("/api/me/sessions").header("Authorization", bearer(again.accessToken())))
                .andExpect(status().isNoContent());
        assertThat(accessTokens.authenticate(token)).isEmpty();
    }

    @Test
    void aTokenDoesNotOpenTheRestOfTheApi() throws Exception {
        Session session = register("api");
        String token = createToken(session, "Claude Code");

        mockMvc.perform(get("/api/workspaces/current").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(TOKENS).header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Recording a use and revoking can happen at the same moment. Writing every column
     * when the use is saved would put the old, unrevoked state back.
     */
    @Test
    void recordingAUseAtTheMomentOfARevocationKeepsTheRevocation() throws Exception {
        Session session = register("race");
        createToken(session, "Claude Code");
        UUID tokenId = UUID.fromString(firstListedId(session));
        TransactionTemplate meanwhile = new TransactionTemplate(transactionManager);
        meanwhile.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            PersonalAccessToken loadedBefore = tokenRepository.findById(tokenId).orElseThrow();
            meanwhile.executeWithoutResult(inner -> tokenRepository.findById(tokenId).orElseThrow()
                    .revoke(Instant.now()));
            loadedBefore.markUsed(Instant.now(), Duration.ofMinutes(5));
        });

        PersonalAccessToken stored = tokenRepository.findById(tokenId).orElseThrow();
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getLastUsedAt()).isNotNull();
    }

    private ResultActions create(Session session, String name, int lifetimeDays) throws Exception {
        return postJson(TOKENS, """
                { "name": "%s", "lifetimeDays": %d }
                """.formatted(name, lifetimeDays), session.accessToken());
    }

    private String createToken(Session session, String name) throws Exception {
        return readJson(create(session, name, 90).andExpect(status().isOk())).get("token").asText();
    }

    private ResultActions revoke(Session session, String tokenId) throws Exception {
        return mockMvc.perform(delete(TOKENS + "/" + tokenId).header("Authorization", bearer(session.accessToken())));
    }

    private String firstListedId(Session session) throws Exception {
        return readJson(mockMvc.perform(get(TOKENS).header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())).get(0).get("id").asText();
    }

    private Session register(String prefix) throws Exception {
        String email = prefix + "+" + uniqueId() + "@example.com";
        ResultActions actions = postJson("/api/auth/register", """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Token User",
                  "workspaceName": "Token Workspace"
                }
                """.formatted(email), null).andExpect(status().isOk());
        JsonNode body = readJson(actions);
        return new Session(email, body.get("accessToken").asText(), refreshToken(actions),
                body.get("workspace").get("id").asText());
    }

    private Session login(String email) throws Exception {
        ResultActions actions = postJson("/api/auth/login", """
                { "email": "%s", "password": "password123" }
                """.formatted(email), null).andExpect(status().isOk());
        JsonNode body = readJson(actions);
        return new Session(email, body.get("accessToken").asText(), refreshToken(actions),
                body.get("workspace").get("id").asText());
    }

    private record Session(String email, String accessToken, String refreshToken, String workspaceId) {
    }
}
