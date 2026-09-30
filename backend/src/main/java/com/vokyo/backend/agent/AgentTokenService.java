package com.vokyo.backend.agent;

import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AgentTokenService {
    public static final String AUDIENCE = "flowai-agent";
    private static final String ISSUER = "flowai";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String WORKSPACE_ID_CLAIM = "workspaceId";
    private static final String MEMBERSHIP_ID_CLAIM = "membershipId";
    private static final String PROJECT_ID_CLAIM = "projectId";
    private static final String RUN_ID_CLAIM = "runId";

    private final JwtEncoder jwtEncoder;

    public AgentTokenService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public String issue(CurrentWorkspaceContext context, UUID projectId, UUID runId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .audience(List.of(AUDIENCE))
            .issuedAt(now)
            .expiresAt(now.plus(TTL))
            .subject(context.user().getId().toString())
            .claim(WORKSPACE_ID_CLAIM, context.workspace().getId().toString())
            .claim(MEMBERSHIP_ID_CLAIM, context.membership().getId().toString())
            .claim(PROJECT_ID_CLAIM, projectId.toString())
            .claim(RUN_ID_CLAIM, runId.toString())
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
