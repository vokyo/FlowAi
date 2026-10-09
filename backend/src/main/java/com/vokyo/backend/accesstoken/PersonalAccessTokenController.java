package com.vokyo.backend.accesstoken;

import com.vokyo.backend.accesstoken.dto.AccessTokenResponse;
import com.vokyo.backend.accesstoken.dto.CreateAccessTokenRequest;
import com.vokyo.backend.accesstoken.dto.CreatedAccessTokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Managed with a normal session; the tokens themselves only work at the MCP endpoint. */
@RestController
@RequestMapping("/api/me/access-tokens")
public class PersonalAccessTokenController {

    private final PersonalAccessTokenService service;

    public PersonalAccessTokenController(PersonalAccessTokenService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccessTokenResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }

    @PostMapping
    public CreatedAccessTokenResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateAccessTokenRequest request
    ) {
        return service.create(jwt, request);
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tokenId) {
        service.revoke(jwt, tokenId);
    }
}
