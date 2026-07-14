package com.vokyo.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.refresh-cookie")
public record RefreshTokenCookieProperties(boolean secure) {
}
