package com.vokyo.backend.auth.dto;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName
) {}