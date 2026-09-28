package com.vokyo.backend.agent;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTokenServiceTests {
    private static final SecretKey KEY = new SecretKeySpec(
        "test-secret-that-is-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"
    );

    private final AgentTokenService agentTokenService =
        new AgentTokenService(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)));
    private final JwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(KEY)
        .macAlgorithm(MacAlgorithm.HS256)
        .build();

    @Test
    void issuesAProjectScopedShortLivedTokenForTheAgentAudience() {
        User user = new User("member@example.com", "unused", "Member");
        Workspace workspace = new Workspace(user, "Workspace", "workspace");
        WorkspaceMembership membership = new WorkspaceMembership(workspace, user, WorkspaceRole.MEMBER);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(workspace, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(membership, "id", UUID.randomUUID());
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        String token = agentTokenService.issue(
            new CurrentWorkspaceContext(user, workspace, membership), projectId, runId);
        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getAudience()).containsExactly("flowai-agent");
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("workspaceId")).isEqualTo(workspace.getId().toString());
        assertThat(jwt.getClaimAsString("membershipId")).isEqualTo(membership.getId().toString());
        assertThat(jwt.getClaimAsString("projectId")).isEqualTo(projectId.toString());
        assertThat(jwt.getClaimAsString("runId")).isEqualTo(runId.toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "role");
    }
}
