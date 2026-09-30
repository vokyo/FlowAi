package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AgentTokenSecurityIntegrationTests {

    private static final String INTERNAL_PATH = "/api/internal/agent/project/issues";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private WorkspaceAccessService workspaceAccessService;
    @Autowired private AgentTokenService agentTokenService;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void userTokenReachesNormalEndpoints() throws Exception {
        String userToken = register("user-ok@example.com");
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isOk());
    }

    @Test
    void agentTokenIsRejectedByNormalEndpoints() throws Exception {
        String agentToken = agentTokenFor(register("agent-normal@example.com"));
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + agentToken))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/ai/suggestions/{id}/apply", UUID.randomUUID())
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenIsRejectedByInternalEndpoints() throws Exception {
        String userToken = register("user-internal@example.com");
        mockMvc.perform(get(INTERNAL_PATH).header("Authorization", "Bearer " + userToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void agentTokenPassesTheInternalChain() throws Exception {
        String agentToken = agentTokenFor(register("agent-internal@example.com"));
        mockMvc.perform(get(INTERNAL_PATH).header("Authorization", "Bearer " + agentToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void validlySignedTokensWithAnUnexpectedAudienceAreRejectedEverywhere() throws Exception {
        String userToken = register("user-audience@example.com");
        String control = resignWithAudience(userToken, List.of("flowai-api"));
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + control))
            .andExpect(status().isOk());

        for (String token : List.of(
            resignWithAudience(userToken, List.of("flowai-other")),
            resignWithAudience(userToken, null))) {
            mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
            mockMvc.perform(get(INTERNAL_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void changingTheAudienceWithoutResigningBreaksTheSignature() throws Exception {
        String agentToken = agentTokenFor(register("agent-tamper@example.com"));
        String[] parts = agentToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload = payload.replace("\"flowai-agent\"", "\"flowai-api\"");
        assertThat(forgedPayload).contains("\"flowai-api\"").doesNotContain("\"flowai-agent\"");
        String forged = parts[0] + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
            + "." + parts[2];
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + forged))
            .andExpect(status().isUnauthorized());
    }

    private String agentTokenFor(String userToken) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwtService.decode(userToken));
        return agentTokenService.issue(context, UUID.randomUUID(), UUID.randomUUID());
    }

    private String resignWithAudience(String userToken, List<String> audience) {
        Jwt original = jwtService.decode(userToken);
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer("flowai")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .subject(original.getSubject())
            .claim("workspaceId", original.getClaimAsString("workspaceId"))
            .claim("membershipId", original.getClaimAsString("membershipId"));
        if (audience != null) {
            claims.audience(audience);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                            {
                              "email": "%s",
                              "password": "password123",
                              "displayName": "Agent Tester",
                              "workspaceName": "Agent Workspace"
                            }
                            """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
