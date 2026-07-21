package com.vokyo.backend.ai;

import org.springframework.http.HttpStatus;

public class AiFeatureException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private AiFeatureException(
            HttpStatus status,
            String code,
            String message
    ) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AiFeatureException providerUnavailable() {
        return new AiFeatureException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_PROVIDER_UNAVAILABLE",
                "AI provider is unavailable"
        );
    }

    public static AiFeatureException invalidResponse() {
        return new AiFeatureException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                "AI provider returned an invalid response"
        );
    }

    public static AiFeatureException timeout() {
        return new AiFeatureException(
                HttpStatus.GATEWAY_TIMEOUT,
                "AI_PROVIDER_TIMEOUT",
                "AI provider request timed out"
        );
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AiFeatureException suggestionNotFound() {
        return new AiFeatureException(
                HttpStatus.NOT_FOUND,
                "AI_SUGGESTION_NOT_FOUND",
                "AI suggestion was not found"
        );
    }

    public static AiFeatureException suggestionNotDraft() {
        return new AiFeatureException(
                HttpStatus.CONFLICT,
                "AI_SUGGESTION_NOT_DRAFT",
                "AI suggestion is not in draft status"
        );
    }

    public static AiFeatureException suggestionInvalid(String message) {
        return new AiFeatureException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "AI_SUGGESTION_INVALID",
                message
        );
    }

    public static AiFeatureException requestInvalid(String message) {
        return new AiFeatureException(
                HttpStatus.BAD_REQUEST,
                "AI_REQUEST_INVALID",
                message
        );
    }
}
