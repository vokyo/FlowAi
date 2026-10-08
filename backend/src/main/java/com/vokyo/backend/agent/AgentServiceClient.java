package com.vokyo.backend.agent;

import com.vokyo.backend.ai.AiFeatureException;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Calls the planning agent: POST /runs to start a run, POST /runs/{runId}/resume to
 * revise its plan, and DELETE /runs/{runId} to drop its checkpoints once it is over.
 * The agent is a separate Python service that reads project data through the
 * internal endpoints with the run's agent token. Starting and revising are never
 * retried, since a run makes several model calls and its result differs from one
 * attempt to the next.
 */
@Component
public class AgentServiceClient {

    private final RestClient restClient;

    public AgentServiceClient(RestClient.Builder restClientBuilder, AgentProperties properties) {
        // The JDK client is chosen explicitly so that a slow agent surfaces as an
        // HttpTimeoutException whatever other HTTP libraries are on the classpath.
        // It is held to HTTP/1.1: by default it offers an h2c upgrade on plain http,
        // which the agent's server does not support and answers by dropping the body.
        this.restClient = restClientBuilder
            .baseUrl(properties.baseUrl())
            .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1))
                .build(ClientHttpRequestFactorySettings.defaults()
                    .withConnectTimeout(properties.connectTimeout())
                    .withReadTimeout(properties.readTimeout())))
            .build();
    }

    public AgentRunResult run(String agentToken, UUID runId, String goal, LocalDate today) {
        return post(agentToken, new RunRequest(runId, goal, today.toString()), "/runs");
    }

    /**
     * Continues the run from the checkpoint where the version under review stopped,
     * with the user's feedback, and answers like a run does.
     */
    public AgentRunResult resume(String agentToken, UUID runId, String checkpointId, String feedback) {
        return post(agentToken, new ResumeRequest(checkpointId, feedback), "/runs/{runId}/resume", runId);
    }

    /** Deletes the run's checkpoints. Throws if the agent does not answer 2xx. */
    public void deleteRun(String agentToken, UUID runId) {
        restClient.delete()
            .uri("/runs/{runId}", runId)
            .headers(headers -> headers.setBearerAuth(agentToken))
            .retrieve()
            .toBodilessEntity();
    }

    private AgentRunResult post(String agentToken, Object body, String uri, Object... uriVariables) {
        AgentRunResult result;
        try {
            result = restClient.post()
                .uri(uri, uriVariables)
                .headers(headers -> headers.setBearerAuth(agentToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(AgentRunResult.class);
        } catch (RestClientResponseException exception) {
            // 503 is the agent saying it cannot run at all, for example without a model
            // key. Any other non-2xx means the request itself was refused.
            if (exception.getStatusCode().value() == 503) {
                throw AiFeatureException.agentUnavailable(exception);
            }
            throw AiFeatureException.agentRunFailed(exception);
        } catch (ResourceAccessException exception) {
            if (isReadTimeout(exception)) {
                throw AiFeatureException.agentTimeout(exception);
            }
            throw AiFeatureException.agentUnavailable(exception);
        } catch (RestClientException exception) {
            throw AiFeatureException.agentInvalidResponse(
                "Planning agent returned a response that does not follow the contract",
                exception
            );
        }
        if (result == null || result.status() == null) {
            throw AiFeatureException.agentInvalidResponse("Planning agent returned no run status");
        }
        return result;
    }

    private static boolean isReadTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            // A connect timeout is an HttpTimeoutException too, but it means the agent
            // was not reachable, which is reported as unavailable instead.
            if (cause instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The request body of POST /runs. {@code today} is the UTC date as YYYY-MM-DD: the
     * model schedules from it and the backend validates the plan's dates against it.
     */
    record RunRequest(UUID runId, String goal, String today) {
    }

    /**
     * The request body of POST /runs/{runId}/resume. checkpointId is where the version
     * being revised stopped; null if the agent did not report one.
     */
    record ResumeRequest(String checkpointId, String feedback) {
    }
}
