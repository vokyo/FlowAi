package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An AI app's view of FlowAI: a real server port, a personal access token created
 * through the API, and the MCP Java SDK's own client speaking Streamable HTTP to
 * /api/mcp. Covers what the app may see, what it is refused, and how hard it may
 * call.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.openai.api-key=dummy",
                "app.security.rate-limit.enabled=true",
                "app.mcp.rate-limit.capacity=10"
        }
)
class McpEndpointIntegrationTests {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anAiAppFindsTheToolsAndReadsTheWorkspaceWithAPersonalAccessToken() throws Exception {
        Workspace workspace = workspace("reader");
        String projectId = project(workspace, "Web Platform");
        issue(workspace, projectId, "Rate limit the login endpoint");
        issue(workspace, projectId, "Ship CSV export");
        String archivedId = project(workspace, "Old Platform");
        send("POST", "/api/projects/" + archivedId + "/archive", workspace.accessToken(), null);
        String token = accessToken(workspace);

        try (McpSyncClient client = client(token)) {
            McpSchema.InitializeResult initialized = client.initialize();
            assertThat(initialized.serverInfo().name()).isEqualTo("flowai");

            List<McpSchema.Tool> tools = client.listTools().tools();
            assertThat(tools).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("list_projects", "search_issues", "list_project_members");
            assertThat(tools).allSatisfy(tool -> assertThat(tool.annotations().readOnlyHint()).isTrue());

            JsonNode projects = json(call(client, "list_projects", Map.of()));
            assertThat(projects.get("projects")).as("archived projects are left out").hasSize(1);
            assertThat(projects.get("projects").get(0).get("id").asText()).isEqualTo(projectId);

            JsonNode found = json(call(client, "search_issues", Map.of("projectId", projectId, "query", "login")));
            assertThat(found.get("items")).hasSize(1);
            assertThat(found.get("items").get(0).get("title").asText()).isEqualTo("Rate limit the login endpoint");

            JsonNode members = json(call(client, "list_project_members", Map.of("projectId", projectId)));
            assertThat(members.get("items").get(0).get("displayName").asText()).isEqualTo("reader user");
        }
    }

    @Test
    void anotherWorkspacesProjectAndBadArgumentsComeBackAsToolErrors() throws Exception {
        Workspace mine = workspace("mine");
        Workspace theirs = workspace("theirs");
        String theirProject = project(theirs, "Their project");
        String token = accessToken(mine);

        try (McpSyncClient client = client(token)) {
            client.initialize();

            CallToolResult foreign = client.callTool(
                    new McpSchema.CallToolRequest("search_issues", Map.of("projectId", theirProject)));
            assertThat(foreign.isError()).isTrue();
            assertThat(text(foreign)).doesNotContain("Their project");

            CallToolResult malformed = client.callTool(
                    new McpSchema.CallToolRequest("list_project_members", Map.of("projectId", "not-an-id")));
            assertThat(malformed.isError()).isTrue();
            assertThat(text(malformed)).isEqualTo("projectId must be a project id from list_projects.");

            assertThat(json(call(client, "list_projects", Map.of())).get("projects")).isEmpty();
        }
    }

    @Test
    void onlyAValidPersonalAccessTokenOpensTheEndpoint() throws Exception {
        Workspace workspace = workspace("gate");
        String token = accessToken(workspace);

        assertThat(rawMcpRequest(null).statusCode()).isEqualTo(401);
        assertThat(rawMcpRequest(workspace.accessToken()).statusCode()).as("a login's access token").isEqualTo(401);
        assertThat(rawMcpRequest(token).statusCode()).isEqualTo(200);

        String tokenId = send("GET", "/api/me/access-tokens", workspace.accessToken(), null).get(0).get("id").asText();
        send("DELETE", "/api/me/access-tokens/" + tokenId, workspace.accessToken(), null);
        assertThat(rawMcpRequest(token).statusCode()).as("a revoked token").isEqualTo(401);
    }

    @Test
    void eachTokenIsLimitedToItsRequestsPerWindow() throws Exception {
        String token = accessToken(workspace("limited"));

        for (int request = 1; request <= 10; request++) {
            assertThat(rawMcpRequest(token).statusCode()).as("request %s", request).isEqualTo(200);
        }
        HttpResponse<String> limited = rawMcpRequest(token);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    private McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/api/mcp")
                .customizeRequest(request -> request.header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("%s: %s", tool, text(result)).isFalse();
        return result;
    }

    private static String text(CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private JsonNode json(CallToolResult result) throws IOException {
        return objectMapper.readTree(text(result));
    }

    /** One JSON-RPC ping, enough to see whether the endpoint lets the caller in. */
    private HttpResponse<String> rawMcpRequest(String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc": "2.0", "id": 1, "method": "ping"}
                        """));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Workspace workspace(String name) throws Exception {
        JsonNode registered = send("POST", "/api/auth/register", null, """
                {"email": "%s+%s@example.com", "password": "password123",
                 "displayName": "%s user", "workspaceName": "%s workspace"}
                """.formatted(name, UUID.randomUUID(), name, name));
        return new Workspace(registered.get("accessToken").asText());
    }

    private String project(Workspace workspace, String name) throws Exception {
        return send("POST", "/api/projects", workspace.accessToken(), """
                {"name": "%s"}
                """.formatted(name)).get("id").asText();
    }

    private void issue(Workspace workspace, String projectId, String title) throws Exception {
        send("POST", "/api/issues", workspace.accessToken(), """
                {"projectId": "%s", "title": "%s"}
                """.formatted(projectId, title));
    }

    private String accessToken(Workspace workspace) throws Exception {
        return send("POST", "/api/me/access-tokens", workspace.accessToken(), """
                {"name": "Test AI app", "lifetimeDays": 30}
                """).get("token").asText();
    }

    private JsonNode send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isBetween(200, 299);
        return response.body().isBlank() ? objectMapper.nullNode() : objectMapper.readTree(response.body());
    }

    private record Workspace(String accessToken) {
    }
}
