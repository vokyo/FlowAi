package com.vokyo.backend.auth;

import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.LoginRequest;
import com.vokyo.backend.auth.dto.RefreshTokenRequest;
import com.vokyo.backend.auth.dto.RegisterRequest;
import com.vokyo.backend.auth.dto.RegisterWithInvitationRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest registerRequest) {
        return authService.register(registerRequest);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest loginRequest) {
        return authService.login(loginRequest);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/register-with-invitation")
    public AuthResponse registerWithInvitation(
            @Valid @RequestBody RegisterWithInvitationRequest request
    ) {
        return authService.registerWithInvitation(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }

}
