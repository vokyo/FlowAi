package com.vokyo.backend.accesstoken.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccessTokenRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Integer lifetimeDays
) {
}
