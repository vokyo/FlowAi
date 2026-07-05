package com.vokyo.backend.auth.dto;

import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String name,
        String slug,
        String role
) {}