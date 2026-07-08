package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.auth.RefreshTokenRepository;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueCommentRepository;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.issue.IssueStatus;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class ProjectIssueWorkflowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private IssueCommentRepository issueCommentRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        activityEventRepository.deleteAllInBatch();
        issueCommentRepository.deleteAllInBatch();
        issueRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        membershipRepository.deleteAllInBatch();
        workspaceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void createsProjectIssueCommentAndActivityTimeline() throws Exception {
        AuthSession session = register("phase2+" + uniqueId() + "@example.com");

        JsonNode project = postJson(
                "/api/projects",
                """
                {
                  "name": "Backend MVP",
                  "description": "Project and issue APIs"
                }
                """,
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Backend MVP"))
                .andExpect(jsonPath("$.description").value("Project and issue APIs"))
                .andReturnJson();

        String projectId = project.get("id").asText();

        mockMvc.perform(get("/api/projects")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(projectId))
                .andExpect(jsonPath("$[0].name").value("Backend MVP"));

        JsonNode issue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Create work APIs",
                  "description": "Wire projects, issues, comments, and activities",
                  "priority": "HIGH"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.title").value("Create work APIs"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.creator.email").value(session.email()))
                .andExpect(jsonPath("$.commentCount").value(0))
                .andReturnJson();

        String issueId = issue.get("id").asText();

        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(issueId))
                .andExpect(jsonPath("$[0].title").value("Create work APIs"))
                .andExpect(jsonPath("$[0].creator.email").value(session.email()));

        JsonNode comment = postJson(
                "/api/issues/%s/comments".formatted(issueId),
                """
                {
                  "body": "This is ready for the frontend."
                }
                """,
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value(issueId))
                .andExpect(jsonPath("$.author.email").value(session.email()))
                .andExpect(jsonPath("$.body").value("This is ready for the frontend."))
                .andReturnJson();

        mockMvc.perform(get("/api/issues/{issueId}", issueId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(issueId))
                .andExpect(jsonPath("$.creator.email").value(session.email()))
                .andExpect(jsonPath("$.comments[0].id").value(comment.get("id").asText()))
                .andExpect(jsonPath("$.comments[0].body").value("This is ready for the frontend."));

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("ISSUE_CREATED"))
                .andExpect(jsonPath("$[0].actor.email").value(session.email()))
                .andExpect(jsonPath("$[1].eventType").value("COMMENT_CREATED"))
                .andExpect(jsonPath("$[1].actor.email").value(session.email()));

        assertThat(projectRepository.findAll()).hasSize(1);
        assertThat(issueRepository.findAll()).hasSize(1);
        assertThat(issueCommentRepository.findAll()).hasSize(1);
        assertThat(activityEventRepository.findAll())
                .extracting(activity -> activity.getEventType().name())
                .containsExactlyInAnyOrder("PROJECT_CREATED", "ISSUE_CREATED", "COMMENT_CREATED");
    }

    @Test
    void createIssueAcceptsStatusAndDefaultsToTodo() throws Exception {
        AuthSession session = register("issue-status+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Status Project").get("id").asText();

        JsonNode inProgressIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Start implementation",
                  "status": "IN_PROGRESS",
                  "priority": "MEDIUM"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andReturnJson();

        JsonNode defaultStatusIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Backlog item"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").doesNotExist())
                .andReturnJson();

        assertThat(issueRepository.findById(UUID.fromString(inProgressIssue.get("id").asText())))
                .get()
                .extracting(Issue::getStatus, Issue::getPriority)
                .containsExactly(IssueStatus.IN_PROGRESS, IssuePriority.MEDIUM);
        assertThat(issueRepository.findById(UUID.fromString(defaultStatusIssue.get("id").asText())))
                .get()
                .extracting(Issue::getStatus, Issue::getPriority)
                .containsExactly(IssueStatus.TODO, null);
    }

    @Test
    void createIssueRejectsArchivedStatus() throws Exception {
        AuthSession session = register("issue-archived-create+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Archived Create Project").get("id").asText();

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Should not start archived",
                  "status": "ARCHIVED"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isBadRequest());

        assertThat(issueRepository.findAll()).isEmpty();
    }

    @Test
    void patchIssueUpdatesStatusPriorityAndRecordsStatusActivity() throws Exception {
        AuthSession session = register("patch-status+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Patch Project").get("id").asText();
        String issueId = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Move through workflow",
                  "status": "TODO",
                  "priority": "LOW"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson()
                .get("id").asText();

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "title": "Workflow shipped",
                  "description": "Done and verified",
                  "status": "DONE",
                  "priority": "URGENT"
                }
                """,
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(issueId))
                .andExpect(jsonPath("$.title").value("Workflow shipped"))
                .andExpect(jsonPath("$.description").value("Done and verified"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.creator.email").value(session.email()))
                .andExpect(jsonPath("$.comments").isArray());

        assertThat(issueRepository.findById(UUID.fromString(issueId)))
                .get()
                .extracting(Issue::getTitle, Issue::getDescription, Issue::getStatus, Issue::getPriority)
                .containsExactly("Workflow shipped", "Done and verified", IssueStatus.DONE, IssuePriority.URGENT);

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("ISSUE_CREATED"))
                .andExpect(jsonPath("$[1].eventType").value("ISSUE_STATUS_CHANGED"))
                .andExpect(jsonPath("$[1].metadata.fromStatus").value("TODO"))
                .andExpect(jsonPath("$[1].metadata.toStatus").value("DONE"));
    }

    @Test
    void patchIssuePriorityOnlyDoesNotRecordStatusActivity() throws Exception {
        AuthSession session = register("patch-priority+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Priority Project").get("id").asText();
        String issueId = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Tune priority",
                  "status": "TODO",
                  "priority": "LOW"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson()
                .get("id").asText();

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "priority": "HIGH"
                }
                """,
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("HIGH"));

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("ISSUE_CREATED"))
                .andExpect(jsonPath("$[1]").doesNotExist());

        assertThat(activityEventRepository.findAll())
                .extracting(activity -> activity.getEventType().name())
                .doesNotContain("ISSUE_STATUS_CHANGED");
    }

    @Test
    void patchIssueClearsNullableFields() throws Exception {
        AuthSession session = register("patch-clear+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Clear Fields Project").get("id").asText();
        String issueId = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Clear optional fields",
                  "description": "Remove this later",
                  "status": "TODO",
                  "priority": "HIGH"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson()
                .get("id").asText();

        JsonNode response = patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "description": null,
                  "priority": null
                }
                """,
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(issueId))
                .andExpect(jsonPath("$.title").value("Clear optional fields"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturnJson();

        assertThat(isMissingOrNull(response, "description")).isTrue();
        assertThat(isMissingOrNull(response, "priority")).isTrue();
        assertThat(issueRepository.findById(UUID.fromString(issueId)))
                .get()
                .extracting(Issue::getDescription, Issue::getPriority)
                .containsExactly(null, null);
    }

    @Test
    void patchIssueEmptyBodyKeepsIssueUnchanged() throws Exception {
        AuthSession session = register("patch-empty+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Empty Patch Project").get("id").asText();
        String issueId = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Keep every field",
                  "description": "This should remain",
                  "status": "IN_PROGRESS",
                  "priority": "MEDIUM"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson()
                .get("id").asText();

        patchJson(
                "/api/issues/%s".formatted(issueId),
                "{}",
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Keep every field"))
                .andExpect(jsonPath("$.description").value("This should remain"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"));

        assertThat(issueRepository.findById(UUID.fromString(issueId)))
                .get()
                .extracting(Issue::getTitle, Issue::getDescription, Issue::getStatus, Issue::getPriority)
                .containsExactly("Keep every field", "This should remain", IssueStatus.IN_PROGRESS, IssuePriority.MEDIUM);
        assertThat(activityEventRepository.findAll())
                .extracting(activity -> activity.getEventType().name())
                .doesNotContain("ISSUE_STATUS_CHANGED");
    }

    @Test
    void patchIssueRejectsInvalidNulls() throws Exception {
        AuthSession session = register("patch-invalid-nulls+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Invalid Null Project").get("id").asText();
        String issueId = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Reject invalid nulls"
                }
                """.formatted(projectId),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson()
                .get("id").asText();

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "title": null
                }
                """,
                session.accessToken()
        ).andExpect(status().isBadRequest());

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "status": null
                }
                """,
                session.accessToken()
        ).andExpect(status().isBadRequest());

        assertThat(issueRepository.findById(UUID.fromString(issueId)))
                .get()
                .extracting(Issue::getTitle, Issue::getStatus)
                .containsExactly("Reject invalid nulls", IssueStatus.TODO);
    }

    @Test
    void listIssuesSupportsFiltersSearchAndArchivedVisibility() throws Exception {
        AuthSession session = register("issue-filter+" + uniqueId() + "@example.com");
        String projectId = createProject(session, "Filtered Project").get("id").asText();

        createIssue(session, projectId, "Fix login flow", "Authentication regression", "TODO", "HIGH");
        createIssue(session, projectId, "Build reports", "Contains billing search term", "IN_PROGRESS", "MEDIUM");
        createIssue(session, projectId, "Release dashboard", "Ready for users", "DONE", "HIGH");
        String archivedIssueId = createIssue(
                session,
                projectId,
                "Archive old import",
                "Legacy cleanup",
                "TODO",
                "LOW"
        ).get("id").asText();

        patchJson(
                "/api/issues/%s".formatted(archivedIssueId),
                """
                {
                  "status": "ARCHIVED"
                }
                """,
                session.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        JsonNode defaultList = getIssues(session, projectId)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(defaultList))
                .containsExactly("Release dashboard", "Build reports", "Fix login flow");

        JsonNode highPriorityIssues = getIssues(session, projectId, null, "HIGH", null)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(highPriorityIssues))
                .containsExactly("Release dashboard", "Fix login flow");

        JsonNode searchedIssues = getIssues(session, projectId, null, null, "billing")
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(searchedIssues))
                .containsExactly("Build reports");

        JsonNode inProgressSearch = getIssues(session, projectId, "IN_PROGRESS", null, "billing")
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(inProgressSearch))
                .containsExactly("Build reports");

        JsonNode archivedIssues = getIssues(session, projectId, "ARCHIVED", null, null)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(archivedIssues))
                .containsExactly("Archive old import");
    }

    @Test
    void rejectsCrossWorkspaceProjectAndIssueAccess() throws Exception {
        AuthSession owner = register("owner+" + uniqueId() + "@example.com");
        AuthSession outsider = register("outsider+" + uniqueId() + "@example.com");

        JsonNode project = postJson(
                "/api/projects",
                """
                {
                  "name": "Private Project"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
        String projectId = project.get("id").asText();

        JsonNode issue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Private issue"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
        String issueId = issue.get("id").asText();

        mockMvc.perform(get("/api/projects")
                        .header("Authorization", bearer(outsider.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(outsider.accessToken())))
                .andExpect(status().isNotFound());

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Cross workspace write"
                }
                """.formatted(projectId),
                outsider.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/issues/{issueId}", issueId)
                        .header("Authorization", bearer(outsider.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(outsider.accessToken())))
                .andExpect(status().isNotFound());

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "status": "DONE",
                  "priority": "HIGH"
                }
                """,
                outsider.accessToken()
        ).andExpect(status().isNotFound());
    }

    private AuthSession register(String email) throws Exception {
        JsonNode response = postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Phase Two User",
                  "workspaceName": "Phase Two Workspace"
                }
                """.formatted(email),
                null
        ).andExpect(status().isOk())
                .andReturnJson();

        return new AuthSession(
                response.get("accessToken").asText(),
                response.get("user").get("email").asText()
        );
    }

    private JsonNode createProject(AuthSession session, String name) throws Exception {
        return postJson(
                "/api/projects",
                """
                {
                  "name": "%s"
                }
                """.formatted(name),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
    }

    private JsonNode createIssue(
            AuthSession session,
            String projectId,
            String title,
            String description,
            String issueStatus,
            String issuePriority
    ) throws Exception {
        return postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "%s",
                  "description": "%s",
                  "status": "%s",
                  "priority": "%s"
                }
                """.formatted(projectId, title, description, issueStatus, issuePriority),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
    }

    private ResultActionsWithJson getIssues(AuthSession session, String projectId) throws Exception {
        return getIssues(session, projectId, null, null, null);
    }

    private ResultActionsWithJson getIssues(
            AuthSession session,
            String projectId,
            String status,
            String priority,
            String query
    ) throws Exception {
        var request = get("/api/issues")
                .queryParam("projectId", projectId)
                .header("Authorization", bearer(session.accessToken()));

        if (status != null) {
            request.queryParam("status", status);
        }
        if (priority != null) {
            request.queryParam("priority", priority);
        }
        if (query != null) {
            request.queryParam("q", query);
        }

        return new ResultActionsWithJson(mockMvc.perform(request));
    }

    private ResultActionsWithJson postJson(String path, String json, String accessToken) throws Exception {
        var request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);

        if (accessToken != null) {
            request.header("Authorization", bearer(accessToken));
        }

        return new ResultActionsWithJson(mockMvc.perform(request));
    }

    private ResultActionsWithJson patchJson(String path, String json, String accessToken) throws Exception {
        var request = patch(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);

        if (accessToken != null) {
            request.header("Authorization", bearer(accessToken));
        }

        return new ResultActionsWithJson(mockMvc.perform(request));
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private String uniqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private boolean isMissingOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull();
    }

    private List<String> issueTitles(JsonNode issues) {
        List<String> titles = new ArrayList<>();
        issues.forEach(issue -> titles.add(issue.get("title").asText()));
        return titles;
    }

    private record AuthSession(String accessToken, String email) {
    }

    private final class ResultActionsWithJson {

        private final org.springframework.test.web.servlet.ResultActions resultActions;

        private ResultActionsWithJson(org.springframework.test.web.servlet.ResultActions resultActions) {
            this.resultActions = resultActions;
        }

        private ResultActionsWithJson andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            resultActions.andExpect(matcher);
            return this;
        }

        private JsonNode andReturnJson() throws Exception {
            MvcResult result = resultActions.andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString());
        }
    }
}
