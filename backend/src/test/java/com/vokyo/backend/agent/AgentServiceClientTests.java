package com.vokyo.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vokyo.backend.ai.AiFeatureException;
import com.vokyo.backend.issue.IssuePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the client against a real HTTP server standing in for the Python agent, so the
 * headers, the JSON on the wire and the timeouts are the real ones.
 */
class AgentServiceClientTests {

    private static final UUID RUN_ID = UUID.fromString("5a0f1c2d-3e4b-4c5d-8e6f-7a8b9c0d1e2f");
    private static final UUID ASSIGNEE = UUID.fromString("7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-04");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Recorded> recorded = new AtomicReference<>();
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private ExecutorService executor;
    private HttpServer server;

    @BeforeEach
    void startAgentStandIn() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/runs", exchange -> {
            recorded.set(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Upgrade"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            ));
            responder.get().respond(exchange);
        });
        server.start();
    }

    @AfterEach
    void stopAgentStandIn() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void sendsTheRunTokenGoalAndDateAndReadsAPlan() throws Exception {
        respondWith(200, """
            {
              "status": "PLANNED",
              "plan": {
                "overview": "Clear the login debt",
                "items": [{
                  "clientItemId": "item-1",
                  "title": "Remove the legacy session table",
                  "description": null,
                  "priority": "HIGH",
                  "suggestedAssigneeUserId": "%s",
                  "dueDate": "2026-10-11"
                }]
              },
              "stats": {"decisionRounds": 2, "toolCalls": 3}
            }
            """.formatted(ASSIGNEE));

        AgentRunResult result = client(Duration.ofSeconds(5)).run("agent-token", RUN_ID, "Clear the login debt", TODAY);

        Recorded request = recorded.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).isEqualTo("Bearer agent-token");
        assertThat(request.contentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("runId").asText()).isEqualTo(RUN_ID.toString());
        assertThat(body.get("goal").asText()).isEqualTo("Clear the login debt");
        assertThat(body.get("today").asText()).isEqualTo("2026-10-04");

        assertThat(result.status()).isEqualTo(AgentRunStatus.PLANNED);
        assertThat(result.plan().items().getFirst().priority()).isEqualTo(IssuePriority.HIGH);
        assertThat(result.plan().items().getFirst().suggestedAssigneeUserId()).isEqualTo(ASSIGNEE);
        assertThat(result.plan().items().getFirst().dueDate()).isEqualTo(LocalDate.parse("2026-10-11"));
        assertThat(result.stats().decisionRounds()).isEqualTo(2);
        assertThat(result.stats().toolCalls()).isEqualTo(3);
    }

    @Test
    void revisesARunFromItsCheckpointWithTheFeedbackAndReadsWhereItStopped() throws Exception {
        respondWith(200, """
            {
              "status": "PLANNED",
              "plan": {"overview": "Clear the login debt", "items": [{
                "clientItemId": "item-1", "title": "Remove the legacy session table",
                "description": null, "priority": "LOW",
                "suggestedAssigneeUserId": null, "dueDate": null}]},
              "stats": {"decisionRounds": 1, "toolCalls": 0},
              "checkpointId": "1f0a-checkpoint-2"
            }
            """);

        AgentRunResult result = client(Duration.ofSeconds(5))
            .resume("agent-token", RUN_ID, "1f0a-checkpoint-1", "Drop item-2");

        Recorded request = recorded.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/runs/" + RUN_ID + "/resume");
        assertThat(request.authorization()).isEqualTo("Bearer agent-token");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("checkpointId").asText()).isEqualTo("1f0a-checkpoint-1");
        assertThat(body.get("feedback").asText()).isEqualTo("Drop item-2");

        assertThat(result.status()).isEqualTo(AgentRunStatus.PLANNED);
        assertThat(result.plan().items()).hasSize(1);
        assertThat(result.checkpointId()).isEqualTo("1f0a-checkpoint-2");
    }

    @Test
    void deletesTheRunsCheckpointsWithTheRunToken() {
        respondWith(204, "");

        client(Duration.ofSeconds(5)).deleteRun("agent-token", RUN_ID);

        Recorded request = recorded.get();
        assertThat(request.method()).isEqualTo("DELETE");
        assertThat(request.path()).isEqualTo("/runs/" + RUN_ID);
        assertThat(request.authorization()).isEqualTo("Bearer agent-token");
    }

    @Test
    void aDeletionTheAgentRefusesThrows() {
        respondWith(500, "{\"detail\": \"database unavailable\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).deleteRun("agent-token", RUN_ID))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void readsARunThatEndedWithoutEnoughInformation() {
        respondWith(200, """
            {"status": "INSUFFICIENT_INFO", "missing": ["No login issues exist yet"],
             "stats": {"decisionRounds": 4, "toolCalls": 5}}
            """);

        AgentRunResult result = client(Duration.ofSeconds(5)).run("agent-token", RUN_ID, "Goal", TODAY);

        assertThat(result.status()).isEqualTo(AgentRunStatus.INSUFFICIENT_INFO);
        assertThat(result.missing()).containsExactly("No login issues exist yet");
        assertThat(result.plan()).isNull();
    }

    @Test
    void speaksPlainHttp11BecauseTheAgentsServerCannotUpgradeToHttp2() {
        respondWith(200, """
            {"status": "INSUFFICIENT_INFO", "missing": ["No login issues exist yet"],
             "stats": {"decisionRounds": 4, "toolCalls": 5}}
            """);

        client(Duration.ofSeconds(5)).run("agent-token", RUN_ID, "Goal", TODAY);

        assertThat(recorded.get().upgrade()).isNull();
    }

    @Test
    void anAgentThatCannotRunIsUnavailable() {
        respondWith(503, "{\"detail\": \"openai api key is required\"}");

        assertAgentError(Duration.ofSeconds(5), "AI_AGENT_UNAVAILABLE");
    }

    @Test
    void aRequestTheAgentRefusesFailsTheRun() {
        respondWith(422, "{\"detail\": [{\"msg\": \"Field required\"}]}");

        assertAgentError(Duration.ofSeconds(5), "AI_AGENT_RUN_FAILED");
    }

    @Test
    void anAnswerOutsideTheContractIsInvalid() {
        respondWith(200, "{\"status\": \"DONE\"}");
        assertAgentError(Duration.ofSeconds(5), "AI_AGENT_INVALID_RESPONSE");

        respondWith(200, "{}");
        assertAgentError(Duration.ofSeconds(5), "AI_AGENT_INVALID_RESPONSE");

        respondWith(200, "<html>maintenance</html>");
        assertAgentError(Duration.ofSeconds(5), "AI_AGENT_INVALID_RESPONSE");
    }

    @Test
    void anAgentSlowerThanTheReadTimeoutTimesOut() {
        responder.set(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            send(exchange, 200, "{\"status\": \"FAILED\", \"reason\": \"too late\"}");
        });

        assertAgentError(Duration.ofMillis(300), "AI_AGENT_TIMEOUT");
    }

    @Test
    void anAgentThatIsNotListeningIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        AgentServiceClient client = new AgentServiceClient(RestClient.builder(), new AgentProperties(
            true,
            URI.create("http://127.0.0.1:" + closedPort),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5)
        ));

        assertThatThrownBy(() -> client.run("agent-token", RUN_ID, "Goal", TODAY))
            .isInstanceOfSatisfying(AiFeatureException.class,
                exception -> assertThat(exception.code()).isEqualTo("AI_AGENT_UNAVAILABLE"));
    }

    @Test
    void aDeploymentWithoutTheAgentRefusesBeforeCallingIt() {
        AgentServiceClient client = new AgentServiceClient(RestClient.builder(), new AgentProperties(
            false,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5)
        ));

        assertThatThrownBy(client::requireEnabled)
            .isInstanceOfSatisfying(AiFeatureException.class, exception -> {
                assertThat(exception.code()).isEqualTo("AI_AGENT_UNAVAILABLE");
                assertThat(exception.status().value()).isEqualTo(503);
            });
    }

    private void assertAgentError(Duration readTimeout, String code) {
        assertThatThrownBy(() -> client(readTimeout).run("agent-token", RUN_ID, "Goal", TODAY))
            .isInstanceOfSatisfying(AiFeatureException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private AgentServiceClient client(Duration readTimeout) {
        return new AgentServiceClient(RestClient.builder(), new AgentProperties(
            true,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            Duration.ofSeconds(2),
            readTimeout
        ));
    }

    private void respondWith(int status, String body) {
        responder.set(exchange -> send(exchange, status, body));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", body.startsWith("<")
            ? "text/html"
            : "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private record Recorded(
        String method,
        String path,
        String authorization,
        String contentType,
        String upgrade,
        String body
    ) {
    }
}
