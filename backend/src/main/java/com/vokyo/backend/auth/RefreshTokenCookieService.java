package com.vokyo.backend.auth;

import com.vokyo.backend.security.JwtProperties;
import com.vokyo.backend.security.RefreshTokenCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@Service
public class RefreshTokenCookieService {

    public static final String COOKIE_NAME = "flowai_refresh_token";
    public static final String COOKIE_PATH = "/api";

    private final RefreshTokenCookieProperties properties;
    private final JwtProperties jwtProperties;

    public RefreshTokenCookieService(
            RefreshTokenCookieProperties properties,
            JwtProperties jwtProperties
    ) {
        this.properties = properties;
        this.jwtProperties = jwtProperties;
    }

    public void write(HttpServletResponse response, String refreshToken) {
        addCookie(response, refreshToken, jwtProperties.refreshTokenTtl());
    }

    public void clear(HttpServletResponse response) {
        addCookie(response, "", Duration.ZERO);
    }

    public String require(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token cookie is required");
        }
        return refreshToken;
    }

    private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
