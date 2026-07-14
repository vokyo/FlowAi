package com.vokyo.backend.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ApiObservability {

    private final MeterRegistry meterRegistry;

    public ApiObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordException(String code, int status) {
        meterRegistry.counter(
                "flowai.api.exceptions",
                "code", code,
                "status", Integer.toString(status)
        ).increment();
    }

    public void recordAuthenticationFailure(String reason) {
        meterRegistry.counter(
                "flowai.authentication.failures",
                "reason", reason
        ).increment();
    }
}
