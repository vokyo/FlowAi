package com.vokyo.backend.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Refuses an access token issued before its user's sessions last ended. The token
 * carries the user's token version from when it was issued, and logging out,
 * changing the password, signing out everywhere and a replayed refresh token each
 * raise it, so older tokens stop working on their next request rather than when they
 * expire. Tokens from before the version existed carry none and count as version 0.
 *
 * The lookup is told the token's version too, so that a cache which has fallen
 * behind the database can be told apart from an old token; see TokenVersionCache.
 */
class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The access token was issued before the user's sessions ended",
            null
    );

    private final BiFunction<UUID, Integer, Optional<Integer>> currentVersion;

    TokenVersionValidator(BiFunction<UUID, Integer, Optional<Integer>> currentVersion) {
        this.currentVersion = currentVersion;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        int issued = issuedVersion(jwt);
        Optional<Integer> current = currentVersion.apply(UUID.fromString(jwt.getSubject()), issued);
        if (current.isEmpty() || current.get() != issued) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static int issuedVersion(Jwt jwt) {
        Object version = jwt.getClaims().get(JwtService.TOKEN_VERSION_CLAIM);
        return version instanceof Number number ? number.intValue() : 0;
    }
}
