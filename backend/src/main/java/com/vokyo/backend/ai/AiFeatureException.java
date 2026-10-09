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
        this(status, code, message, null);
    }

    /**
     * The message and code stay generic on purpose — callers see only that the
     * provider failed. The cause is kept so the failure is still diagnosable from
     * the server log.
     */
    private AiFeatureException(
            HttpStatus status,
            String code,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public static AiFeatureException providerUnavailable() {
        return providerUnavailable(null);
    }

    public static AiFeatureException providerUnavailable(Throwable cause) {
        return new AiFeatureException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_PROVIDER_UNAVAILABLE",
                "AI provider is unavailable",
                cause
        );
    }

    public static AiFeatureException invalidResponse() {
        return invalidResponse(null);
    }

    public static AiFeatureException invalidResponse(Throwable cause) {
        return new AiFeatureException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                "AI provider returned an invalid response",
                cause
        );
    }

    public static AiFeatureException timeout() {
        return timeout(null);
    }

    public static AiFeatureException timeout(Throwable cause) {
        return new AiFeatureException(
                HttpStatus.GATEWAY_TIMEOUT,
                "AI_PROVIDER_TIMEOUT",
                "AI provider request timed out",
                cause
        );
    }

    public static AiFeatureException agentUnavailable(Throwable cause) {
        return new AiFeatureException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_AGENT_UNAVAILABLE",
                "Planning agent is unavailable",
                cause
        );
    }

    public static AiFeatureException agentTimeout(Throwable cause) {
        return new AiFeatureException(
                HttpStatus.GATEWAY_TIMEOUT,
                "AI_AGENT_TIMEOUT",
                "Planning agent did not answer in time",
                cause
        );
    }

    public static AiFeatureException agentRunFailed() {
        return agentRunFailed(null);
    }

    public static AiFeatureException agentRunFailed(Throwable cause) {
        return new AiFeatureException(
                HttpStatus.BAD_GATEWAY,
                "AI_AGENT_RUN_FAILED",
                "Planning agent could not finish the run",
                cause
        );
    }

    public static AiFeatureException agentInvalidResponse(String message) {
        return agentInvalidResponse(message, null);
    }

    public static AiFeatureException agentInvalidResponse(String message, Throwable cause) {
        return new AiFeatureException(
                HttpStatus.BAD_GATEWAY,
                "AI_AGENT_INVALID_RESPONSE",
                message,
                cause
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

    public static AiFeatureException agentRunNotFound() {
        return new AiFeatureException(
                HttpStatus.NOT_FOUND,
                "AI_AGENT_RUN_NOT_FOUND",
                "Planning run was not found"
        );
    }

    /** The same person is already running the agent on the project, from this or another instance. */
    public static AiFeatureException agentRunInProgress() {
        return new AiFeatureException(
                HttpStatus.CONFLICT,
                "AI_AGENT_RUN_IN_PROGRESS",
                "A planning run of yours on this project is still going"
        );
    }

    /** The run was approved or cancelled, so its plan can no longer change. */
    public static AiFeatureException agentRunClosed(String message) {
        return new AiFeatureException(
                HttpStatus.CONFLICT,
                "AI_AGENT_RUN_CLOSED",
                message
        );
    }

    public static AiFeatureException planVersionOutdated(int requestedVersion, int latestVersion) {
        return new AiFeatureException(
                HttpStatus.CONFLICT,
                "AI_PLAN_VERSION_OUTDATED",
                "Version " + requestedVersion + " was replaced by version " + latestVersion
        );
    }

    public static AiFeatureException planVersionChanged() {
        return new AiFeatureException(
                HttpStatus.CONFLICT,
                "AI_PLAN_VERSION_CHANGED",
                "The plan's content hash does not match this version; reload the run"
        );
    }

    public static AiFeatureException planVersionLimitReached(int maxVersions) {
        return new AiFeatureException(
                HttpStatus.CONFLICT,
                "AI_PLAN_VERSION_LIMIT",
                "A run has at most " + maxVersions + " versions; approve or cancel it"
        );
    }

    public static AiFeatureException planVersionNotApprovable(String reason) {
        return new AiFeatureException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "AI_PLAN_VERSION_NOT_APPROVABLE",
                reason
        );
    }

    /** Project plans belong to their run, which is where they are approved or cancelled. */
    public static AiFeatureException projectPlanBelongsToRun() {
        return new AiFeatureException(
                HttpStatus.CONFLICT,
                "AI_PROJECT_PLAN_BELONGS_TO_RUN",
                "Project plans are approved or cancelled through their planning run"
        );
    }
}
