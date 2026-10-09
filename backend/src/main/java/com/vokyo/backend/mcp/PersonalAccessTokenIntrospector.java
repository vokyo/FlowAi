package com.vokyo.backend.mcp;

import com.vokyo.backend.accesstoken.AccessTokenPrincipal;
import com.vokyo.backend.accesstoken.PersonalAccessTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the bearer token an AI app sends to the MCP endpoint into the user and
 * workspace it speaks for. Personal access tokens are opaque, not JWTs, so Spring
 * Security asks this introspector instead of decoding them.
 */
class PersonalAccessTokenIntrospector implements OpaqueTokenIntrospector {

    private static final String CALLER = "caller";

    private final PersonalAccessTokenService accessTokens;

    PersonalAccessTokenIntrospector(PersonalAccessTokenService accessTokens) {
        this.accessTokens = accessTokens;
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        AccessTokenPrincipal caller = accessTokens.authenticate(token)
                .orElseThrow(() -> new BadOpaqueTokenException("The personal access token is not valid"));
        return new OAuth2IntrospectionAuthenticatedPrincipal(
                caller.userId().toString(),
                Map.of(CALLER, caller),
                List.of(new SimpleGrantedAuthority("SCOPE_mcp"))
        );
    }

    /** The caller behind a request this introspector authenticated. */
    static Optional<AccessTokenPrincipal> caller(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal introspected
                && introspected.getAttribute(CALLER) instanceof AccessTokenPrincipal caller) {
            return Optional.of(caller);
        }
        return Optional.empty();
    }
}
