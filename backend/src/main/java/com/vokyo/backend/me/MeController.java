package com.vokyo.backend.me;

import com.vokyo.backend.me.dto.MeResponse;
import com.vokyo.backend.me.dto.UpdateProfileRequest;
import com.vokyo.backend.me.dto.ChangePasswordRequest;
import com.vokyo.backend.auth.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
public class MeController {

    private final MeService meService;
    private final AccountService accountService;

    public MeController(MeService meService, AccountService accountService) {
        this.meService = meService;
        this.accountService = accountService;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return meService.getCurrentSession(jwt);
    }

    @PatchMapping("/api/me/profile")
    public UserResponse updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return accountService.updateProfile(jwt, request);
    }

    @PutMapping("/api/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        accountService.changePassword(jwt, request);
    }

    @DeleteMapping("/api/me/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAllSessions(@AuthenticationPrincipal Jwt jwt) {
        accountService.revokeAllSessions(jwt);
    }
}
