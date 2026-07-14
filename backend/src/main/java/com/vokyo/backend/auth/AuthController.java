package com.vokyo.backend.auth;

import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.LoginRequest;
import com.vokyo.backend.auth.dto.RegisterRequest;
import com.vokyo.backend.auth.dto.RegisterWithInvitationRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    public AuthController(
            AuthService authService,
            RefreshTokenCookieService refreshTokenCookieService
    ) {
        this.authService = authService;
        this.refreshTokenCookieService = refreshTokenCookieService;
    }

    @PostMapping("/register")
    public AuthResponse register(
            @Valid @RequestBody RegisterRequest registerRequest,
            HttpServletResponse response
    ) {
        return writeSession(authService.register(registerRequest), response);
    }

    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletResponse response
    ) {
        return writeSession(authService.login(loginRequest), response);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(
            @CookieValue(name = RefreshTokenCookieService.COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        return writeSession(authService.refresh(refreshTokenCookieService.require(refreshToken)), response);
    }

    @PostMapping("/register-with-invitation")
    public AuthResponse registerWithInvitation(
            @Valid @RequestBody RegisterWithInvitationRequest request
            , HttpServletResponse response
    ) {
        return writeSession(authService.registerWithInvitation(request), response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = RefreshTokenCookieService.COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        refreshTokenCookieService.clear(response);
    }

    private AuthResponse writeSession(AuthSessionResult session, HttpServletResponse response) {
        refreshTokenCookieService.write(response, session.refreshToken());
        return session.response();
    }

}
