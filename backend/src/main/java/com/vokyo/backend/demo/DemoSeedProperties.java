package com.vokyo.backend.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Controls the demo dataset a public deployment shows instead of an empty
 * workspace. Off unless {@code DEMO_SEED_ENABLED} says otherwise, so a normal
 * deployment — and every developer machine — is untouched.
 *
 * <p>The credentials are properties rather than constants so that moving the demo
 * to another platform is a matter of environment variables alone.
 */
@ConfigurationProperties(prefix = "app.demo")
@Validated
public record DemoSeedProperties(

        boolean enabled,

        @NotBlank
        @Size(max = 255)
        String email,

        /**
         * Also the password of the seeded teammates, so a visitor can sign in as
         * any of them and see the workspace from another seat.
         */
        @NotBlank
        @Size(min = 8, max = 72)
        String password,

        @NotBlank
        @Size(max = 160)
        String workspaceName
) {
}
