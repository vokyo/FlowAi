package com.vokyo.backend.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;

public final class AudienceJwtDecoders {

    private AudienceJwtDecoders() {
    }

    @SafeVarargs
    public static JwtDecoder forAudience(
            SecretKey secretKey,
            String audience,
            OAuth2TokenValidator<Jwt>... extraValidators
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>(List.of(
            JwtValidators.createDefault(),
            new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> List.of(audience).equals(aud))
        ));
        validators.addAll(List.of(extraValidators));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }
}
