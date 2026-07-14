package com.vokyo.backend.security.ratelimit;

import java.time.Duration;

public enum RateLimitPolicy {
    LOGIN_IP("login_ip", 20, Duration.ofMinutes(1)),
    LOGIN_EMAIL_FAILURE("login_email_failure", 10, Duration.ofMinutes(15)),
    REGISTRATION_IP("registration_ip", 10, Duration.ofHours(1)),
    REFRESH_IP("refresh_ip", 60, Duration.ofMinutes(1)),
    INVITATION_PREVIEW_IP("invitation_preview_ip", 60, Duration.ofMinutes(1));

    private final String metricName;
    private final long capacity;
    private final Duration window;

    RateLimitPolicy(String metricName, long capacity, Duration window) {
        this.metricName = metricName;
        this.capacity = capacity;
        this.window = window;
    }

    public String metricName() {
        return metricName;
    }

    public long capacity() {
        return capacity;
    }

    public Duration window() {
        return window;
    }
}
