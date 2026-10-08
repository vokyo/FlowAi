package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.agent.AgentRunRepository;
import com.vokyo.backend.agent.AgentRunState;
import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.integration.AgentRunIntegrationTests.AgentStandIn;
import com.vokyo.backend.issue.IssueRepository;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reviews a run the way a user would: revising its plan, approving a version, or
 * cancelling it, with a real HTTP server standing in for the agent. Checks that only
 * the latest version can be approved, only once, and that nothing is written to the
 * project before that.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AgentRunReviewIntegrationTests extends AbstractMockMvcIntegrationTest {

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
    @Autowired private AiSuggestionRepository suggestionRepository;
    @Autowired private AgentRunRepository runRepository;
    @Autowired private JwtService jwtService;
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
    void aRevisionContinuesFromWhereTheVersionUnderReviewStoppedAndReplacesIt() throws Exception {
        Graph graph = graph("revise");
        answerEveryRunAndRevision();
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();

        revise(graph, runId, 1, "  Drop the documentation task  ")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLANNED"))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.approvable").value(true))
            .andExpect(jsonPath("$.plan.items.length()").value(2));

        AgentStandIn.Received revision = AGENT.received().get(1);
        assertThat(revision.path()).isEqualTo("/runs/" + runId + "/resume");
        assertThat(revision.body().get("checkpointId").asText()).isEqualTo("checkpoint-1");
        assertThat(revision.body().get("feedback").asText()).isEqualTo("Drop the documentation task");
        Jwt agentToken = AudienceJwtDecoders.forAudience(jwtSecretKey, AgentTokenService.AUDIENCE)
            .decode(revision.bearerToken());
        assertThat(agentToken.getClaimAsString("runId")).isEqualTo(runId);
        assertThat(agentToken.getClaimAsString("projectId")).isEqualTo(graph.project().getId().toString());

        assertThat(statusOfDraft(runId, 1)).isEqualTo(AiSuggestionStatus.DISMISSED);
        assertThat(statusOfDraft(runId, 2)).isEqualTo(AiSuggestionStatus.DRAFT);
        getRun(graph, runId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("REVIEWING"))
            .andExpect(jsonPath("$.latestVersion").value(2))
            .andExpect(jsonPath("$.versions.length()").value(2))
            .andExpect(jsonPath("$.versions[1].plan.items.length()").value(2));

        revise(graph, runId, 2, "Add a task for the login audit")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(3));
        assertThat(AGENT.received().get(2).body().get("checkpointId").asText()).isEqualTo("checkpoint-2");
        assertThat(AGENT.deletions()).isEmpty();
    }

    @Test
    void nothingIsWrittenToTheProjectUntilAVersionIsApproved() throws Exception {
        Graph graph = graph("no-writes");
        answerEveryRunAndRevision();
        long issuesBefore = issueRepository.count();

        JsonNode first = start(graph);
        String runId = first.get("runId").asText();
        readJson(revise(graph, runId, 1, "Drop the documentation task").andExpect(status().isOk()));
        JsonNode third = readJson(revise(graph, runId, 2, "Keep it to one task").andExpect(status().isOk()));
        assertThat(issueRepository.count()).isEqualTo(issuesBefore);

        approve(graph, runId, 3, third.get("contentHash").asText())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.createdIssueIds.length()").value(1));
        assertThat(issueRepository.count()).isEqualTo(issuesBefore + 1);
        assertThat(runRepository.findById(UUID.fromString(runId)).orElseThrow().getState())
            .isEqualTo(AgentRunState.APPROVED);
    }

    @Test
    void aReplacedVersionCanBeNeitherApprovedNorRevised() throws Exception {
        Graph graph = graph("replaced");
        answerEveryRunAndRevision();
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();
        revise(graph, runId, 1, "Drop the documentation task").andExpect(status().isOk());

        approve(graph, runId, 1, first.get("contentHash").asText())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_PLAN_VERSION_OUTDATED"))
            .andExpect(jsonPath("$.message").value("Version 1 was replaced by version 2"));
        revise(graph, runId, 1, "Another change based on the old version")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_PLAN_VERSION_OUTDATED"));

        assertThat(AGENT.received()).hasSize(2);
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    void approvingTheSameVersionTwiceCreatesItsIssuesOnce() throws Exception {
        Graph graph = graph("twice");
        answerEveryRunAndRevision();
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();
        String hash = first.get("contentHash").asText();

        JsonNode approved = readJson(approve(graph, runId, 1, hash).andExpect(status().isOk()));
        approve(graph, runId, 1, hash)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.createdIssueIds").value(contains(toArray(approved.get("createdIssueIds")))));

        assertThat(issueRepository.count()).isEqualTo(3);
    }

    @Test
    void approvingARunTellsTheAgentToDropItsCheckpointsOnce() throws Exception {
        Graph graph = graph("approve-deletes");
        answerEveryRunAndRevision();
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();

        approve(graph, runId, 1, first.get("contentHash").asText()).andExpect(status().isOk());
        approve(graph, runId, 1, first.get("contentHash").asText()).andExpect(status().isOk());

        assertThat(AGENT.deletions()).containsExactly("/runs/" + runId);
        AgentStandIn.Received deletion = AGENT.received().getLast();
        Jwt agentToken = AudienceJwtDecoders.forAudience(jwtSecretKey, AgentTokenService.AUDIENCE)
            .decode(deletion.bearerToken());
        assertThat(agentToken.getClaimAsString("runId")).isEqualTo(runId);
    }

    @Test
    void anAgentThatFailsToDropTheCheckpointsDoesNotFailTheApproval() throws Exception {
        Graph graph = graph("delete-fails");
        answerEveryRunAndRevision();
        AGENT.answerDeletes(new AgentStandIn.Reply(500, "{\"detail\": \"database unavailable\"}"));
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();

        approve(graph, runId, 1, first.get("contentHash").asText())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.createdIssueIds.length()").value(3));

        assertThat(AGENT.deletions()).containsExactly("/runs/" + runId);
        assertThat(runRepository.findById(UUID.fromString(runId)).orElseThrow().getState())
            .isEqualTo(AgentRunState.APPROVED);
    }

    @Test
    void approvalNeedsTheHashOfTheVersionTheUserRead() throws Exception {
        Graph graph = graph("hash");
        answerEveryRunAndRevision();
        String runId = start(graph).get("runId").asText();

        approve(graph, runId, 1, "0".repeat(64))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_PLAN_VERSION_CHANGED"));

        assertThat(issueRepository.count()).isZero();
        assertThat(statusOfDraft(runId, 1)).isEqualTo(AiSuggestionStatus.DRAFT);
    }

    @Test
    void aRunTakesAtMostFiveVersions() throws Exception {
        Graph graph = graph("five");
        answerEveryRunAndRevision();
        String runId = start(graph).get("runId").asText();
        for (int version = 1; version < 5; version++) {
            revise(graph, runId, version, "Change " + version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 1));
        }

        revise(graph, runId, 5, "One change too many")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_PLAN_VERSION_LIMIT"));

        assertThat(AGENT.received()).hasSize(5);
        getRun(graph, runId).andExpect(jsonPath("$.latestVersion").value(5));
    }

    @Test
    void aRevisionThatEndsWithoutAPlanLeavesTheRunAsItWas() throws Exception {
        Graph graph = graph("no-plan");
        AtomicInteger calls = new AtomicInteger();
        AGENT.answer(request -> switch (calls.incrementAndGet()) {
            case 1 -> planned("checkpoint-1", "Remove the legacy session table", "Add refresh token tests");
            case 2 -> ok("""
                {"status": "INSUFFICIENT_INFO", "missing": ["Which audit do you mean?"],
                 "stats": {"decisionRounds": 2, "toolCalls": 4}}
                """);
            default -> ok("""
                {"status": "FAILED", "reason": "backend unavailable after retries",
                 "stats": {"decisionRounds": 1, "toolCalls": 3}}
                """);
        });
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();

        revise(graph, runId, 1, "Add the audit")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INSUFFICIENT_INFO"))
            .andExpect(jsonPath("$.missing[0]").value("Which audit do you mean?"))
            .andExpect(jsonPath("$.version").doesNotExist());
        revise(graph, runId, 1, "Add the audit")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_FAILED"));

        getRun(graph, runId)
            .andExpect(jsonPath("$.latestVersion").value(1))
            .andExpect(jsonPath("$.versions.length()").value(1));
        assertThat(statusOfDraft(runId, 1)).isEqualTo(AiSuggestionStatus.DRAFT);
        approve(graph, runId, 1, first.get("contentHash").asText()).andExpect(status().isOk());
    }

    @Test
    void aRevisedPlanThatBreaksARuleIsKeptButCannotBeApproved() throws Exception {
        Graph graph = graph("broken-revision");
        AtomicInteger calls = new AtomicInteger();
        AGENT.answer(request -> calls.incrementAndGet() == 1
            ? planned("checkpoint-1", "Remove the legacy session table")
            : ok("""
                {"status": "PLANNED", "checkpointId": "checkpoint-2",
                 "plan": {"overview": "Clear the login debt", "items": [
                   {"clientItemId": "item-1", "title": "Remove the legacy session table",
                    "description": null, "priority": "LOW",
                    "suggestedAssigneeUserId": "%s", "dueDate": null}]},
                 "stats": {"decisionRounds": 1, "toolCalls": 0}}
                """.formatted(UUID.randomUUID())));
        String runId = start(graph).get("runId").asText();

        JsonNode second = readJson(revise(graph, runId, 1, "Give it to the new hire")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.approvable").value(false))
            .andExpect(jsonPath("$.rejectionReason").value(containsString("is not an active project member"))));

        approve(graph, runId, 2, second.get("contentHash").asText())
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AI_PLAN_VERSION_NOT_APPROVABLE"));
        // Only the latest version can be approved, so the run moves on by revising again.
        revise(graph, runId, 2, "Leave it unassigned").andExpect(status().isOk());
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    void cancellingEndsTheRunWithoutWritingAnything() throws Exception {
        Graph graph = graph("cancel");
        answerEveryRunAndRevision();
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();

        cancel(graph, runId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CANCELLED"));
        assertThat(statusOfDraft(runId, 1)).isEqualTo(AiSuggestionStatus.DISMISSED);

        approve(graph, runId, 1, first.get("contentHash").asText())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_CLOSED"));
        revise(graph, runId, 1, "Too late")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_CLOSED"));
        cancel(graph, runId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CANCELLED"));

        // The first cancellation told the agent to drop the run's checkpoints; the second did not repeat it.
        assertThat(AGENT.deletions()).containsExactly("/runs/" + runId);
        assertThat(AGENT.received()).hasSize(2);
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    void anApprovedRunCanBeNeitherCancelledNorRevised() throws Exception {
        Graph graph = graph("approved");
        answerEveryRunAndRevision();
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();
        approve(graph, runId, 1, first.get("contentHash").asText()).andExpect(status().isOk());

        cancel(graph, runId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_CLOSED"));
        revise(graph, runId, 1, "One more change")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_CLOSED"));
        assertThat(issueRepository.count()).isEqualTo(3);
    }

    @Test
    void aRunIsNotFoundForAnyoneButTheUserWhoStartedIt() throws Exception {
        Graph graph = graph("owner");
        answerEveryRunAndRevision();
        JsonNode first = start(graph);
        String runId = first.get("runId").asText();
        String teammate = jwtService.generateAccessToken(graph.teammate(), graph.teammateMembership());

        mockMvc.perform(get(RUNS + "/" + runId).header("Authorization", bearer(teammate)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_NOT_FOUND"));
        postJson(RUNS + "/" + runId + "/approve", approvalBody(1, first.get("contentHash").asText()), teammate)
            .andExpect(status().isNotFound());
        postJson(RUNS + "/" + runId + "/revisions", revisionBody(1, "Mine now"), teammate)
            .andExpect(status().isNotFound());
        postJson(RUNS + "/" + runId + "/cancel", "{}", teammate)
            .andExpect(status().isNotFound());

        assertThat(AGENT.received()).hasSize(1);
        getRun(graph, runId).andExpect(jsonPath("$.state").value("REVIEWING"));
    }

    @Test
    void aRevisionIsDiscardedIfTheRunWasCancelledWhileTheAgentWorkedOnIt() throws Exception {
        Graph graph = graph("cancelled-meanwhile");
        AtomicInteger calls = new AtomicInteger();
        AGENT.answer(request -> {
            if (calls.incrementAndGet() == 1) {
                return planned("checkpoint-1", "Remove the legacy session table");
            }
            // The user cancels in another tab while the agent is still revising.
            jdbcTemplate.update("update agent_runs set state = 'CANCELLED'");
            return planned("checkpoint-2", "Remove the legacy session table", "Add refresh token tests");
        });
        String runId = start(graph).get("runId").asText();

        revise(graph, runId, 1, "Add the tests")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AI_AGENT_RUN_CLOSED"));

        getRun(graph, runId)
            .andExpect(jsonPath("$.state").value("CANCELLED"))
            .andExpect(jsonPath("$.versions.length()").value(1));
    }

    private void answerEveryRunAndRevision() {
        AtomicInteger checkpoints = new AtomicInteger();
        AGENT.answer(request -> {
            String checkpoint = "checkpoint-" + checkpoints.incrementAndGet();
            return switch (checkpoints.get()) {
                case 1 -> planned(checkpoint,
                    "Remove the legacy session table", "Add refresh token tests", "Document the login flow");
                case 2 -> planned(checkpoint, "Remove the legacy session table", "Add refresh token tests");
                default -> planned(checkpoint, "Remove the legacy session table");
            };
        });
    }

    private static AgentStandIn.Reply planned(String checkpointId, String... titles) {
        String items = IntStream.range(0, titles.length)
            .mapToObj(index -> """
                {"clientItemId": "item-%d", "title": "%s", "description": null, "priority": "MEDIUM",
                 "suggestedAssigneeUserId": null, "dueDate": null}
                """.formatted(index + 1, titles[index]))
            .collect(Collectors.joining(","));
        return ok("""
            {"status": "PLANNED", "checkpointId": "%s",
             "plan": {"overview": "Clear the login module's technical debt", "items": [%s]},
             "stats": {"decisionRounds": 2, "toolCalls": 2}}
            """.formatted(checkpointId, items));
    }

    private static AgentStandIn.Reply ok(String body) {
        return new AgentStandIn.Reply(200, body);
    }

    private JsonNode start(Graph graph) throws Exception {
        return readJson(postJson(RUNS, """
                { "projectId": "%s", "goal": "Clear the login debt" }
                """.formatted(graph.project().getId()), graph.accessToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1)));
    }

    private ResultActions revise(Graph graph, String runId, int basedOnVersion, String feedback) throws Exception {
        return postJson(RUNS + "/" + runId + "/revisions", revisionBody(basedOnVersion, feedback), graph.accessToken());
    }

    private ResultActions approve(Graph graph, String runId, int version, String contentHash) throws Exception {
        return postJson(RUNS + "/" + runId + "/approve", approvalBody(version, contentHash), graph.accessToken());
    }

    private ResultActions cancel(Graph graph, String runId) throws Exception {
        return postJson(RUNS + "/" + runId + "/cancel", "{}", graph.accessToken());
    }

    private ResultActions getRun(Graph graph, String runId) throws Exception {
        return mockMvc.perform(get(RUNS + "/" + runId).header("Authorization", bearer(graph.accessToken())));
    }

    private static String revisionBody(int basedOnVersion, String feedback) {
        return """
            { "basedOnVersion": %d, "feedback": "%s" }
            """.formatted(basedOnVersion, feedback);
    }

    private static String approvalBody(int version, String contentHash) {
        return """
            { "version": %d, "contentHash": "%s" }
            """.formatted(version, contentHash);
    }

    private AiSuggestionStatus statusOfDraft(String runId, int version) {
        UUID suggestionId = jdbcTemplate.queryForObject(
            "select suggestion_id from agent_plan_versions where run_id = ? and version = ?",
            UUID.class, UUID.fromString(runId), version
        );
        return suggestionRepository.findById(suggestionId).orElseThrow().getStatus();
    }

    private static String[] toArray(JsonNode array) {
        String[] values = new String[array.size()];
        for (int index = 0; index < array.size(); index++) {
            values[index] = array.get(index).asText();
        }
        return values;
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
        WorkspaceMembership teammateMembership = membershipRepository.save(new WorkspaceMembership(
            workspace, teammate, WorkspaceRole.MEMBER));
        Project project = projectRepository.save(new Project(
            workspace, owner, "Project " + suffix, "Project description"));
        projectMemberRepository.save(new ProjectMember(workspace, project, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(workspace, project, teammate, ProjectRole.MEMBER));
        workflowStateRepository.save(new ProjectWorkflowState(
            workspace, project, "Todo", WorkflowStateCategory.TODO, 10_000));
        return new Graph(owner, teammate, teammateMembership, project,
            jwtService.generateAccessToken(owner, membership));
    }

    private record Graph(
        User owner,
        User teammate,
        WorkspaceMembership teammateMembership,
        Project project,
        String accessToken
    ) {
    }
}
