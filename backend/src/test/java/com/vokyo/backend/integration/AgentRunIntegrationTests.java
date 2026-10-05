package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueCreationCommand;
import com.vokyo.backend.issue.IssueCreationService;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.issue.IssueStatus;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.security.AudienceJwtDecoders;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts planning runs through the real security chain, with a real HTTP server
 * standing in for the Python agent, so the token, the request body and every way a
 * run can end are checked on the wire.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AgentRunIntegrationTests extends AbstractMockMvcIntegrationTest {

    private static final String RUNS = "/api/agent/runs";
    private static final AgentStandIn AGENT = AgentStandIn.start();

    @DynamicPropertySource
    static void pointTheBackendAtTheStandIn(DynamicPropertyRegistry registry) {
        registry.add("app.agent.base-url", AGENT::baseUrl);
    }

    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectWorkflowStateRepository workflowStateRepository;
    @Autowired private IssueRepository issueRepository;
    @Autowired private IssueCreationService issueCreationService;
    @Autowired private AiSuggestionRepository suggestionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private AgentTokenService agentTokenService;
    @Autowired private SecretKey jwtSecretKey;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTheStandIn() {
        AGENT.reset();
    }

    @AfterAll
    static void stopTheStandIn() {
        AGENT.stop();
    }

    @Test
    void aPlannedRunIsSavedAsADraftPlanThatTheUserCanApprove() throws Exception {
        Graph graph = graph("planned");
        AGENT.answer(request -> planned(request, graph.teammate().getId()));

        JsonNode response = readJson(startRun(graph, graph.project().getId(), "  Clear the login debt  ")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLANNED"))
            .andExpect(jsonPath("$.plan.items.length()").value(3))
            .andExpect(jsonPath("$.missing.length()").value(0))
            .andExpect(jsonPath("$.stats.toolCalls").value(3)));
        String runId = response.get("runId").asText();

        AgentStandIn.Received received = AGENT.single();
        assertThat(received.body().get("runId").asText()).isEqualTo(runId);
        assertThat(received.body().get("goal").asText()).isEqualTo("Clear the login debt");
        assertThat(received.body().get("today").asText())
            .isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        Jwt agentToken = AudienceJwtDecoders.forAudience(jwtSecretKey, AgentTokenService.AUDIENCE)
            .decode(received.bearerToken());
        assertThat(agentToken.getSubject()).isEqualTo(graph.owner().getId().toString());
        assertThat(agentToken.getClaimAsString("projectId")).isEqualTo(graph.project().getId().toString());
        assertThat(agentToken.getClaimAsString("runId")).isEqualTo(runId);

        AiSuggestion suggestion = suggestionRepository
            .findById(UUID.fromString(response.get("suggestionId").asText()))
            .orElseThrow();
        assertThat(suggestion.getType()).isEqualTo(AiSuggestionType.PROJECT_PLAN);
        assertThat(suggestion.getStatus()).isEqualTo(AiSuggestionStatus.DRAFT);
        assertThat(suggestion.getSourceIssue()).isNull();
        assertThat(suggestion.getCreatedByUser().getId()).isEqualTo(graph.owner().getId());
        assertThat(issueRepository.count()).isZero();

        postJson(
            "/api/ai/suggestions/%s/apply".formatted(suggestion.getId()),
            "{ \"idempotencyKey\": \"%s\" }".formatted(UUID.randomUUID()),
            graph.accessToken()
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.createdIssueIds.length()").value(3));
        assertThat(jdbcTemplate.queryForObject(
            "select assignee_user_id from issues where title = ?",
            UUID.class,
            "Add refresh token tests"
        )).isEqualTo(graph.teammate().getId());
    }

    @Test
    void aPlanCanNameExistingIssuesAndApprovingItCreatesOnlyTheNewTasks() throws Exception {
        Graph graph = graph("reuse");
        Issue csvExport = existingIssue(graph, graph.project(), "Export a project's issues as CSV");
        AGENT.answer(request -> plannedReusing(csvExport.getId(), ONE_NEW_TASK));

        JsonNode response = readJson(startRun(graph, graph.project().getId(), "Ship CSV export")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLANNED"))
            .andExpect(jsonPath("$.plan.existingIssues[0].issueId").value(csvExport.getId().toString()))
            .andExpect(jsonPath("$.plan.existingIssues[0].reason").value("This is the CSV export"))
            .andExpect(jsonPath("$.plan.items.length()").value(1)));

        postJson(
            "/api/ai/suggestions/%s/apply".formatted(response.get("suggestionId").asText()),
            "{ \"idempotencyKey\": \"%s\" }".formatted(UUID.randomUUID()),
            graph.accessToken()
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.createdIssueIds.length()").value(1));
        assertThat(issueRepository.count()).isEqualTo(2);
        assertThat(issueRepository.findById(csvExport.getId()).orElseThrow().getTitle())
            .isEqualTo("Export a project's issues as CSV");
    }

    @Test
    void aPlanNamingAnIssueOfAnotherProjectIsRejected() throws Exception {
        Graph graph = graph("foreign-issue");
        Project other = projectRepository.save(new Project(
            graph.workspace(), graph.owner(), "Other project", "Another project in the same workspace"));
        workflowStateRepository.save(new ProjectWorkflowState(
            graph.workspace(), other, "Todo", WorkflowStateCategory.TODO, 10_000));
        Issue foreign = existingIssue(graph, other, "Export a project's issues as CSV");
        AGENT.answer(request -> plannedReusing(foreign.getId(), ONE_NEW_TASK));

        startRun(graph, graph.project().getId(), "Ship CSV export")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("AI_AGENT_INVALID_RESPONSE"))
            .andExpect(jsonPath("$.message").value(containsString("is not an active issue of this project")));
        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void aPlanThatOnlyNamesExistingIssuesIsSavedButHasNothingToApprove() throws Exception {
        Graph graph = graph("nothing-new");
        Issue csvExport = existingIssue(graph, graph.project(), "Export a project's issues as CSV");
        AGENT.answer(request -> plannedReusing(csvExport.getId(), "[]"));

        JsonNode response = readJson(startRun(graph, graph.project().getId(), "Ship CSV export")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLANNED"))
            .andExpect(jsonPath("$.plan.items.length()").value(0)));

        postJson(
            "/api/ai/suggestions/%s/apply".formatted(response.get("suggestionId").asText()),
            "{ \"idempotencyKey\": \"%s\" }".formatted(UUID.randomUUID()),
            graph.accessToken()
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("dismiss it instead")));
        assertThat(issueRepository.count()).isEqualTo(1);
    }

    @Test
    void approvingAPlanWhoseExistingIssueWasArchivedSinceIsRejected() throws Exception {
        Graph graph = graph("archived-since");
        Issue csvExport = existingIssue(graph, graph.project(), "Export a project's issues as CSV");
        AGENT.answer(request -> plannedReusing(csvExport.getId(), ONE_NEW_TASK));
        JsonNode response = readJson(startRun(graph, graph.project().getId(), "Ship CSV export")
            .andExpect(status().isOk()));

        Issue archived = issueRepository.findById(csvExport.getId()).orElseThrow();
        archived.archive();
        issueRepository.saveAndFlush(archived);

        postJson(
            "/api/ai/suggestions/%s/apply".formatted(response.get("suggestionId").asText()),
            "{ \"idempotencyKey\": \"%s\" }".formatted(UUID.randomUUID()),
            graph.accessToken()
        )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value(containsString("is not an active issue of this project")));
        assertThat(issueRepository.count()).isEqualTo(1);
    }

    @Test
    void aRunWithoutEnoughInformationSavesNothing() throws Exception {
        Graph graph = graph("insufficient");
        AGENT.answer(request -> ok("""
            {"status": "INSUFFICIENT_INFO",
             "missing": ["  No issue mentions the login module  "],
             "stats": {"decisionRounds": 4, "toolCalls": 6}}
            """));

        startRun(graph, graph.project().getId(), "Clear the login debt")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INSUFFICIENT_INFO"))
            .andExpect(jsonPath("$.missing[0]").value("No issue mentions the login module"))
            .andExpect(jsonPath("$.suggestionId").doesNotExist())
            .andExpect(jsonPath("$.plan").doesNotExist());
        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void aRunTheAgentCouldNotFinishIsAnErrorAndSavesNothing() throws Exception {
        Graph graph = graph("failed");
        AGENT.answer(request -> ok("""
            {"status": "FAILED", "reason": "backend unavailable after retries",
             "stats": {"decisionRounds": 1, "toolCalls": 3}}
            """));

        startRun(graph, graph.project().getId(), "Clear the login debt")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_FAILED"));
        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void aPlanThatAssignsSomeoneOutsideTheProjectIsRejected() throws Exception {
        Graph graph = graph("stranger-assignee");
        AGENT.answer(request -> planned(request, UUID.randomUUID()));

        startRun(graph, graph.project().getId(), "Clear the login debt")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("AI_AGENT_INVALID_RESPONSE"))
            .andExpect(jsonPath("$.message").value(containsString("is not an active project member")));
        assertThat(suggestionRepository.count()).isZero();
    }

    @Test
    void anInsufficientAnswerThatDoesNotSayWhatIsMissingIsRejected() throws Exception {
        Graph graph = graph("empty-missing");
        AGENT.answer(request -> ok("{\"status\": \"INSUFFICIENT_INFO\", \"missing\": []}"));

        startRun(graph, graph.project().getId(), "Clear the login debt")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("AI_AGENT_INVALID_RESPONSE"));
    }

    @Test
    void aProjectTheUserCannotReadNeverReachesTheAgent() throws Exception {
        Graph owner = graph("owner");
        Graph stranger = graph("stranger");

        startRun(stranger, owner.project().getId(), "Clear the login debt")
            .andExpect(status().isNotFound());
        assertThat(AGENT.received()).isEmpty();
    }

    @Test
    void anArchivedProjectCannotStartARun() throws Exception {
        Graph graph = graph("archived");
        Project project = projectRepository.findById(graph.project().getId()).orElseThrow();
        project.archive();
        projectRepository.saveAndFlush(project);

        startRun(graph, graph.project().getId(), "Clear the login debt")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AI_REQUEST_INVALID"));
        assertThat(AGENT.received()).isEmpty();
    }

    @Test
    void anAgentTokenCannotStartARun() throws Exception {
        Graph graph = graph("agent-token");
        String agentToken = agentTokenService.issue(
            new CurrentWorkspaceContext(graph.owner(), graph.workspace(), graph.membership()),
            graph.project().getId(),
            UUID.randomUUID()
        );

        postJson(RUNS, runBody(graph.project().getId(), "Clear the login debt"), agentToken)
            .andExpect(status().isUnauthorized());
        assertThat(AGENT.received()).isEmpty();
    }

    @Test
    void aBlankGoalIsRejectedBeforeTheAgentIsCalled() throws Exception {
        Graph graph = graph("blank-goal");

        startRun(graph, graph.project().getId(), "   ")
            .andExpect(status().isBadRequest());
        assertThat(AGENT.received()).isEmpty();
    }

    private ResultActions startRun(Graph graph, UUID projectId, String goal) throws Exception {
        return postJson(RUNS, runBody(projectId, goal), graph.accessToken());
    }

    private static String runBody(UUID projectId, String goal) {
        return """
            { "projectId": "%s", "goal": "%s" }
            """.formatted(projectId, goal);
    }

    private static AgentStandIn.Reply planned(JsonNode request, UUID assignee) {
        LocalDate dueDate = LocalDate.parse(request.get("today").asText()).plusDays(7);
        return ok("""
            {
              "status": "PLANNED",
              "plan": {
                "overview": "Clear the login module's technical debt",
                "items": [
                  {"clientItemId": "item-1", "title": "Remove the legacy session table",
                   "description": "Drop it once nothing reads it", "priority": "MEDIUM",
                   "suggestedAssigneeUserId": null, "dueDate": null},
                  {"clientItemId": "item-2", "title": "Add refresh token tests",
                   "description": "Cover rotation and reuse detection", "priority": "HIGH",
                   "suggestedAssigneeUserId": "%s", "dueDate": "%s"},
                  {"clientItemId": "item-3", "title": "Document the login flow",
                   "description": null, "priority": "LOW",
                   "suggestedAssigneeUserId": null, "dueDate": null}
                ]
              },
              "stats": {"decisionRounds": 2, "toolCalls": 3}
            }
            """.formatted(assignee, dueDate));
    }

    private static final String ONE_NEW_TASK = """
        [{"clientItemId": "item-1", "title": "Add tests for the CSV export",
          "description": null, "priority": "MEDIUM",
          "suggestedAssigneeUserId": null, "dueDate": null}]
        """;

    private static AgentStandIn.Reply plannedReusing(UUID existingIssueId, String items) {
        return ok("""
            {
              "status": "PLANNED",
              "plan": {
                "overview": "Ship CSV export",
                "existingIssues": [{"issueId": "%s", "reason": "  This is the CSV export  "}],
                "items": %s
              },
              "stats": {"decisionRounds": 2, "toolCalls": 2}
            }
            """.formatted(existingIssueId, items));
    }

    private Issue existingIssue(Graph graph, Project project, String title) {
        return issueCreationService.create(
            new CurrentWorkspaceContext(graph.owner(), graph.workspace(), graph.membership()),
            project,
            new IssueCreationCommand(title, null, List.of(), null, null, IssueStatus.TODO, IssuePriority.LOW, null)
        );
    }

    private static AgentStandIn.Reply ok(String body) {
        return new AgentStandIn.Reply(200, body);
    }

    private Graph graph(String suffix) {
        long unique = System.nanoTime();
        User owner = userRepository.save(new User(
            suffix + "-" + unique + "@example.com", "password-hash", "Owner"));
        User teammate = userRepository.save(new User(
            suffix + "-teammate-" + unique + "@example.com", "password-hash", "Teammate"));
        Workspace workspace = workspaceRepository.save(new Workspace(
            owner, "Workspace " + suffix, suffix + "-" + unique));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
            workspace, owner, WorkspaceRole.OWNER));
        membershipRepository.save(new WorkspaceMembership(workspace, teammate, WorkspaceRole.MEMBER));
        Project project = projectRepository.save(new Project(
            workspace, owner, "Project " + suffix, "Project description"));
        projectMemberRepository.save(new ProjectMember(workspace, project, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(workspace, project, teammate, ProjectRole.MEMBER));
        workflowStateRepository.save(new ProjectWorkflowState(
            workspace, project, "Todo", WorkflowStateCategory.TODO, 10_000));
        return new Graph(owner, teammate, workspace, membership, project,
            jwtService.generateAccessToken(owner, membership));
    }

    private record Graph(
        User owner,
        User teammate,
        Workspace workspace,
        WorkspaceMembership membership,
        Project project,
        String accessToken
    ) {
    }

    /**
     * A real HTTP server answering POST /runs the way each test asks, and recording
     * what the backend sent it.
     */
    static final class AgentStandIn {

        private static final ObjectMapper JSON = new ObjectMapper();
        private static final Function<JsonNode, Reply> UNSET =
            request -> new Reply(500, "{\"detail\": \"no answer set for this test\"}");

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private volatile Function<JsonNode, Reply> answer = UNSET;

        private AgentStandIn(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static AgentStandIn start() {
            try {
                HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                server.setExecutor(executor);
                AgentStandIn standIn = new AgentStandIn(server, executor);
                server.createContext("/runs", standIn::handle);
                server.start();
                return standIn;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void answer(Function<JsonNode, Reply> answer) {
            this.answer = answer;
        }

        void reset() {
            received.clear();
            answer = UNSET;
        }

        List<Received> received() {
            return received;
        }

        Received single() {
            assertThat(received).hasSize(1);
            return received.getFirst();
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            received.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"), body));
            Reply reply = answer.apply(body);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        record Received(String authorization, JsonNode body) {
            String bearerToken() {
                return authorization.substring("Bearer ".length());
            }
        }

        record Reply(int status, String body) {
        }
    }
}
