package com.vokyo.backend.auth;

import com.vokyo.backend.security.JwtProperties;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.WorkspaceMembership;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final RefreshTokenReuseHandler reuseHandler;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtProperties jwtProperties,
            RefreshTokenReuseHandler reuseHandler
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.reuseHandler = reuseHandler;
    }

    @Transactional
    public String createRefreshToken(User user, WorkspaceMembership membership) {
        String plainToken = generatePlainToken();
        String tokenHash = hashToken(plainToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());

        RefreshToken refreshToken = new RefreshToken(user, membership, tokenHash, expiresAt);
        refreshTokenRepository.save(refreshToken);

        return plainToken;
    }

    @Transactional
    public RefreshToken requireActiveTokenForUpdate(String plainToken) {
        String tokenHash = hashToken(plainToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> unauthorized("Invalid refresh token"));

        if (refreshToken.isActive()) {
            return refreshToken;
        }

        /*
         * The caller sees one 401 either way, but the two ways of getting here are not
         * the same event. A token that is simply unknown or expired carries no
         * information. A token that was rotated away and then came back means someone
         * used it twice, which rotation is supposed to make impossible — so the chain
         * is treated as compromised rather than merely rejected. The grace window
         * keeps tabs that refreshed concurrently out of that verdict.
         */
        if (refreshToken.wasRevokedBefore(Instant.now().minus(jwtProperties.refreshReuseGrace()))) {
            reuseHandler.onReuseDetected(refreshToken);
        }

        throw unauthorized("Invalid refresh token");
    }

    public String hashToken(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(plainToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    @Transactional
    public void revoke(String plainToken) {
        refreshTokenRepository.findByTokenHashForUpdate(hashToken(plainToken))
                .ifPresent(token -> {
                    if (!token.isRevoked()) {
                        token.revoke();
                    }
                });
    }

    @Transactional
    public void revokeMembershipSessions(UUID membershipId) {
        refreshTokenRepository.revokeAllByMembershipId(membershipId, Instant.now());
    }

    @Transactional
    public void revokeUserSessions(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
    }

    private String generatePlainToken() {
        byte[] tokenBytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
