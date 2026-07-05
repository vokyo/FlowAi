package com.vokyo.backend.auth;

import com.vokyo.backend.security.JwtProperties;
import com.vokyo.backend.user.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class RefreshTokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public String createRefreshToken(User user) {
        String plainToken = generatePlainToken();
        String tokenHash = hashToken(plainToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());

        RefreshToken refreshToken = new RefreshToken(user, tokenHash, expiresAt);
        refreshTokenRepository.save(refreshToken);

        return plainToken;
    }

    @Transactional(readOnly = true)
    public RefreshToken requireActiveToken(String plainToken) {
        String tokenHash = hashToken(plainToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (!refreshToken.isActive()) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        return refreshToken;
    }

    @Transactional
    public void revoke(String plainToken) {
        String tokenHash = hashToken(plainToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(RefreshToken::isActive)
                .ifPresent(RefreshToken::revoke);
    }

    @Transactional
    public String rotate(String plainToken) {
        RefreshToken refreshToken = requireActiveToken(plainToken);
        refreshToken.revoke();
        return createRefreshToken(refreshToken.getUser());
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

    private String generatePlainToken() {
        byte[] tokenBytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
