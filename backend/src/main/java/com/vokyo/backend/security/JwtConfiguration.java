package com.vokyo.backend.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.vokyo.backend.user.UserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, RefreshTokenCookieProperties.class})
class JwtConfiguration {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(secret, HMAC_ALGORITHM);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        ImmutableSecret<SecurityContext> jwkSource = new ImmutableSecret<>(jwtSecretKey);
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Decodes the users' access tokens. Besides the signature, expiry and audience, it
     * checks each token against its user's current token version, one primary-key read
     * per request, so a logout ends the token at once. The agent's tokens go through a
     * decoder of their own and are not affected.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, UserRepository userRepository) {
        return AudienceJwtDecoders.forAudience(
                jwtSecretKey,
                JwtService.API_AUDIENCE,
                new TokenVersionValidator(userRepository::findTokenVersionById)
        );
    }
}
