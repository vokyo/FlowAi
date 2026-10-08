package com.vokyo.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenVersionValidatorTests {

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void acceptsATokenIssuedUnderTheUsersCurrentVersion() {
        assertThat(validate(withVersion(3), 3).hasErrors()).isFalse();
    }

    @Test
    void refusesATokenIssuedBeforeTheUsersSessionsLastEnded() {
        OAuth2TokenValidatorResult result = validate(withVersion(2), 3);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .singleElement()
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
    }

    @Test
    void countsATokenFromBeforeTheVersionExistedAsVersionZero() {
        Jwt unversioned = token(Map.of());

        assertThat(validate(unversioned, 0).hasErrors()).isFalse();
        assertThat(validate(unversioned, 1).hasErrors()).isTrue();
    }

    @Test
    void refusesATokenWhoseUserNoLongerExists() {
        OAuth2TokenValidatorResult result = new TokenVersionValidator(id -> Optional.empty())
                .validate(withVersion(0));

        assertThat(result.hasErrors()).isTrue();
    }

    private static OAuth2TokenValidatorResult validate(Jwt jwt, int currentVersion) {
        return new TokenVersionValidator(id -> id.equals(USER_ID) ? Optional.of(currentVersion) : Optional.empty())
                .validate(jwt);
    }

    private static Jwt withVersion(int version) {
        return token(Map.of(JwtService.TOKEN_VERSION_CLAIM, version));
    }

    private static Jwt token(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .claims(all -> all.putAll(claims))
                .build();
    }
}
