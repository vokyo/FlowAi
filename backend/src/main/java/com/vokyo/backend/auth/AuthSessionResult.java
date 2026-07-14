package com.vokyo.backend.auth;

import com.vokyo.backend.auth.dto.AuthResponse;

public record AuthSessionResult(
        AuthResponse response,
        String refreshToken
) {
}
