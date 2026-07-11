package com.vokyo.backend.workspace;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.workspace")
public record WorkspaceInvitationProperties(
        @NotNull
        Duration invitationTtl
) {
}
