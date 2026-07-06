package com.vokyo.backend.me.dto;

import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.auth.dto.WorkspaceResponse;

public record MeResponse(
        UserResponse user,
        WorkspaceResponse workspace
) {}
