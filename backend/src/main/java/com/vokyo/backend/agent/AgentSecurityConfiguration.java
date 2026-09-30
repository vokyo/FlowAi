package com.vokyo.backend.agent;

import com.vokyo.backend.security.AudienceJwtDecoders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import javax.crypto.SecretKey;

@Configuration
public class AgentSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain agentInternalSecurityFilterChain(
        HttpSecurity http,
        SecretKey jwtSecretKey,
        AuthenticationEntryPoint apiAuthenticationEntryPoint,
        AccessDeniedHandler apiAccessDeniedHandler
    ) throws Exception {
        JwtDecoder agentJwtDecoder = AudienceJwtDecoders.forAudience(jwtSecretKey, AgentTokenService.AUDIENCE);
        return http
            .securityMatcher("/api/internal/agent/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(apiAuthenticationEntryPoint)
                .accessDeniedHandler(apiAccessDeniedHandler)
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(apiAuthenticationEntryPoint)
                .accessDeniedHandler(apiAccessDeniedHandler)
                .jwt(jwt -> jwt.decoder(agentJwtDecoder))
            )
            .build();
    }
}
