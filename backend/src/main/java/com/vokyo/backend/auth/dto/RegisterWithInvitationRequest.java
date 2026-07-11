package com.vokyo.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterWithInvitationRequest(
        @NotBlank
        String token,

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(max = 120)
        String displayName,

        @NotBlank
        @Size(min = 8, max = 72)
        String password
) {
}
