package com.vokyo.backend.security;

import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class JwtService {

    private static final String ISSUER = "flowai";
    private static final String EMAIL_CLAIM = "email";
    private static final String WORKSPACE_ID_CLAIM = "workspaceId";
    private static final String MEMBERSHIP_ID_CLAIM = "membershipId";
    private static final String ROLE_CLAIM = "role";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final JwtProperties jwtProperties;

    public JwtService(JwtEncoder jwtEncoder, JwtDecoder jwtDecoder, JwtProperties jwtProperties) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.jwtProperties = jwtProperties;
    }

    public String generateAccessToken(User user, WorkspaceMembership membership) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .subject(user.getId().toString())
                .claim(EMAIL_CLAIM, user.getEmail())
                .claim(WORKSPACE_ID_CLAIM, membership.getWorkspace().getId().toString())
                .claim(MEMBERSHIP_ID_CLAIM, membership.getId().toString())
                .claim(ROLE_CLAIM, membership.getRole().name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }

    public boolean isValid(String token) {
        try {
            decode(token);
            return true;
        } catch (JwtException ex) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(decode(token).getSubject());
    }

    public UUID getWorkspaceId(String token) {
        return getUuidClaim(decode(token), WORKSPACE_ID_CLAIM);
    }

    public UUID getMembershipId(String token) {
        return getUuidClaim(decode(token), MEMBERSHIP_ID_CLAIM);
    }

    public WorkspaceRole getRole(String token) {
        return WorkspaceRole.valueOf(decode(token).getClaimAsString(ROLE_CLAIM));
    }

    public Instant getExpiresAt(String token) {
        return decode(token).getExpiresAt();
    }

    private UUID getUuidClaim(Jwt jwt, String claimName) {
        return UUID.fromString(jwt.getClaimAsString(claimName));
    }
}
