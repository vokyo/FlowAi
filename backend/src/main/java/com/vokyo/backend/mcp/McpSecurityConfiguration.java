package com.vokyo.backend.mcp;

import com.vokyo.backend.accesstoken.PersonalAccessTokenService;
import com.vokyo.backend.security.ratelimit.RateLimitService;
import com.vokyo.backend.web.ApiErrorWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The MCP endpoint takes personal access tokens and nothing else: an access token from
 * a login is a JWT the introspector does not know, and a personal access token is not
 * a JWT the rest of the API could decode.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpSecurityConfiguration {

    static final String ENDPOINT = "/api/mcp";

    @Bean
    @Order(2)
    SecurityFilterChain mcpSecurityFilterChain(
        HttpSecurity http,
        PersonalAccessTokenService accessTokens,
        RateLimitService rateLimitService,
        McpProperties properties,
        ApiErrorWriter errorWriter,
        AuthenticationEntryPoint apiAuthenticationEntryPoint,
        AccessDeniedHandler apiAccessDeniedHandler
    ) throws Exception {
        return http
            .securityMatcher(ENDPOINT, ENDPOINT + "/**")
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
                .opaqueToken(opaque -> opaque.introspector(new PersonalAccessTokenIntrospector(accessTokens)))
            )
            .addFilterAfter(
                new McpRateLimitFilter(rateLimitService, properties.rateLimit(), errorWriter),
                BearerTokenAuthenticationFilter.class
            )
            .build();
    }
}
