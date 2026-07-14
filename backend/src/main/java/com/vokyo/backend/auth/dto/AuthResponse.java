package com.vokyo.backend.auth.dto;

public record AuthResponse(
        String accessToken,
        UserResponse user,
        WorkspaceResponse workspace
) {}
