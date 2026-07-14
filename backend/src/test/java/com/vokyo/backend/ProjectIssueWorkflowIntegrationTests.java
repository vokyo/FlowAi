package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueCommentRepository;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.issue.IssueStatus;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private ProjectLabelRepository projectLabelRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private ProjectWorkflowStateRepository projectWorkflowStateRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
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
                .andExpect(jsonPath("$.workflowState.name").value("Todo"))
                .andExpect(jsonPath("$.workflowState.category").value("TODO"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.creator.email").value(session.email()))
                .andExpect(jsonPath("$.commentCount").value(0))
                .andReturnJson();

        String issueId = issue.get("id").asText();

        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(issueId))
                .andExpect(jsonPath("$.items[0].title").value("Create work APIs"))
                .andExpect(jsonPath("$.items[0].creator.email").value(session.email()))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

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
                .andExpect(jsonPath("$.comments").doesNotExist());

        mockMvc.perform(get("/api/issues/{issueId}/comments", issueId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(comment.get("id").asText()))
                .andExpect(jsonPath("$.items[0].body").value("This is ready for the frontend."));

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("COMMENT_CREATED"))
                .andExpect(jsonPath("$.items[0].actor.email").value(session.email()))
                .andExpect(jsonPath("$.items[1].eventType").value("ISSUE_CREATED"))
                .andExpect(jsonPath("$.items[1].actor.email").value(session.email()));

        assertThat(projectRepository.findAll()).hasSize(1);
        assertThat(projectWorkflowStateRepository.findAll())
                .extracting(workflowState -> workflowState.getName())
                .containsExactlyInAnyOrder("Todo", "In progress", "Done");
        assertThat(projectMemberRepository.findAll())
                .singleElement()
                .satisfies(member -> {
                    assertThat(member.getRole()).isEqualTo(ProjectRole.OWNER);
                    assertThat(member.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
                });
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
                .andExpect(jsonPath("$.workflowState.name").value("In progress"))
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
                .andExpect(jsonPath("$.workflowState.name").value("Todo"))
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
                .andExpect(jsonPath("$.comments").doesNotExist());

        assertThat(issueRepository.findById(UUID.fromString(issueId)))
                .get()
                .extracting(Issue::getTitle, Issue::getDescription, Issue::getStatus, Issue::getPriority)
                .containsExactly("Workflow shipped", "Done and verified", IssueStatus.DONE, IssuePriority.URGENT);

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("ISSUE_PRIORITY_CHANGED"))
                .andExpect(jsonPath("$.items[0].metadata.fromPriority").value("LOW"))
                .andExpect(jsonPath("$.items[0].metadata.toPriority").value("URGENT"))
                .andExpect(jsonPath("$.items[1].eventType").value("ISSUE_STATUS_CHANGED"))
                .andExpect(jsonPath("$.items[1].metadata.fromStatus").value("Todo"))
                .andExpect(jsonPath("$.items[1].metadata.toStatus").value("Done"))
                .andExpect(jsonPath("$.items[1].metadata.fromWorkflowStateId").isString())
                .andExpect(jsonPath("$.items[1].metadata.toWorkflowStateId").isString())
                .andExpect(jsonPath("$.items[2].eventType").value("ISSUE_TITLE_CHANGED"))
                .andExpect(jsonPath("$.items[2].metadata.fromTitle").value("Move through workflow"))
                .andExpect(jsonPath("$.items[2].metadata.toTitle").value("Workflow shipped"))
                .andExpect(jsonPath("$.items[3].eventType").value("ISSUE_CREATED"));
    }

    @Test
    void patchIssuePriorityOnlyRecordsPriorityActivityButNotStatusActivity() throws Exception {
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
                .andExpect(jsonPath("$.items[0].eventType").value("ISSUE_PRIORITY_CHANGED"))
                .andExpect(jsonPath("$.items[0].metadata.fromPriority").value("LOW"))
                .andExpect(jsonPath("$.items[0].metadata.toPriority").value("HIGH"))
                .andExpect(jsonPath("$.items[1].eventType").value("ISSUE_CREATED"))
                .andExpect(jsonPath("$.items[2]").doesNotExist());

        assertThat(activityEventRepository.findAll())
                .extracting(activity -> activity.getEventType().name())
                .contains("ISSUE_PRIORITY_CHANGED")
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
    void issueAssigneeAndDueDateCanBeCreatedFilteredUpdatedAndCleared() throws Exception {
        AuthSession owner = register("issue-assignee-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "issue-assignee-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Assigned Issue Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));

        JsonNode assignedIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Assigned issue",
                  "assigneeUserId": "%s",
                  "dueDate": "2026-08-01"
                }
                """.formatted(projectId, userId(member)),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Assigned issue"))
                .andExpect(jsonPath("$.assignee.id").value(userId(member)))
                .andExpect(jsonPath("$.assignee.email").value(member.email()))
                .andExpect(jsonPath("$.dueDate").value("2026-08-01"))
                .andReturnJson();

        createIssue(owner, projectId, "Unassigned issue", "No owner", "TODO", "LOW");

        JsonNode assignedIssues = getIssues(owner, projectId, null, null, null, userId(member))
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(assignedIssues)).containsExactly("Assigned issue");

        String issueId = assignedIssue.get("id").asText();
        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "title": "Assigned issue updated",
                  "priority": "HIGH",
                  "assigneeUserId": "%s",
                  "dueDate": "2026-08-05"
                }
                """.formatted(userId(owner)),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Assigned issue updated"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.assignee.id").value(userId(owner)))
                .andExpect(jsonPath("$.dueDate").value("2026-08-05"));

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("ISSUE_DUE_DATE_CHANGED"))
                .andExpect(jsonPath("$.items[0].metadata.fromDueDate").value("2026-08-01"))
                .andExpect(jsonPath("$.items[0].metadata.toDueDate").value("2026-08-05"))
                .andExpect(jsonPath("$.items[1].eventType").value("ISSUE_ASSIGNEE_CHANGED"))
                .andExpect(jsonPath("$.items[1].metadata.fromAssigneeUserId").value(userId(member)))
                .andExpect(jsonPath("$.items[1].metadata.toAssigneeUserId").value(userId(owner)))
                .andExpect(jsonPath("$.items[2].eventType").value("ISSUE_PRIORITY_CHANGED"))
                .andExpect(jsonPath("$.items[3].eventType").value("ISSUE_TITLE_CHANGED"))
                .andExpect(jsonPath("$.items[4].eventType").value("ISSUE_CREATED"));

        JsonNode clearedIssue = patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "assigneeUserId": null,
                  "dueDate": null
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();

        assertThat(isMissingOrNull(clearedIssue, "assignee")).isTrue();
        assertThat(isMissingOrNull(clearedIssue, "dueDate")).isTrue();
        assertThat(issueRepository.findById(UUID.fromString(issueId)))
                .get()
                .satisfies(issue -> {
                    assertThat(issue.getAssigneeUser()).isNull();
                    assertThat(issue.getDueDate()).isNull();
                });
    }

    @Test
    void issueAssigneeMustBeActiveProjectMember() throws Exception {
        AuthSession owner = register("assignee-owner+" + uniqueId() + "@example.com");
        AuthSession workspaceMember = createWorkspaceMember(owner, "assignee-workspace-member+" + uniqueId() + "@example.com");
        AuthSession projectMember = createWorkspaceMember(owner, "assignee-project-member+" + uniqueId() + "@example.com");
        AuthSession outsider = register("assignee-outsider+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Assignee Guard Project").get("id").asText();
        addProjectMember(owner, projectId, userId(projectMember));
        var disabledProjectMember = projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
                        workspaceId(owner),
                        UUID.fromString(projectId),
                        UUID.fromString(userId(projectMember)),
                        MembershipStatus.ACTIVE
                )
                .orElseThrow();
        disabledProjectMember.disable();
        projectMemberRepository.save(disabledProjectMember);

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Assign workspace member",
                  "assigneeUserId": "%s"
                }
                """.formatted(projectId, userId(workspaceMember)),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Assign disabled project member",
                  "assigneeUserId": "%s"
                }
                """.formatted(projectId, userId(projectMember)),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Assign outsider",
                  "assigneeUserId": "%s"
                }
                """.formatted(projectId, userId(outsider)),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        String issueId = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Valid issue"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson()
                .get("id").asText();

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "assigneeUserId": "%s"
                }
                """.formatted(userId(workspaceMember)),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "dueDate": "not-a-date"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isBadRequest());
    }

    @Test
    void projectMembersCanCreateAndListProjectLabels() throws Exception {
        AuthSession owner = register("label-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "label-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Labels Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));

        JsonNode bugLabel = postJson(
                "/api/projects/%s/labels".formatted(projectId),
                """
                {
                  "name": "Bug",
                  "color": "#ef4444"
                }
                """,
                member.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.name").value("Bug"))
                .andExpect(jsonPath("$.color").value("#ef4444"))
                .andReturnJson();

        postJson(
                "/api/projects/%s/labels".formatted(projectId),
                """
                {
                  "name": "Design"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("#64748b"));

        mockMvc.perform(get("/api/projects/{projectId}/labels", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bug"))
                .andExpect(jsonPath("$[1].name").value("Design"));

        postJson(
                "/api/projects/%s/labels".formatted(projectId),
                """
                {
                  "name": "bug",
                  "color": "#f97316"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isConflict());

        assertThat(bugLabel.get("id").asText()).isNotBlank();
        assertThat(projectLabelRepository.findAll()).hasSize(2);
    }

    @Test
    void projectLabelsAreHiddenFromNonProjectMembers() throws Exception {
        AuthSession owner = register("label-hidden-owner+" + uniqueId() + "@example.com");
        AuthSession nonProjectMember = createWorkspaceMember(
                owner,
                "label-hidden-member+" + uniqueId() + "@example.com"
        );
        String projectId = createProject(owner, "Hidden Labels Project").get("id").asText();

        mockMvc.perform(get("/api/projects/{projectId}/labels", projectId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        postJson(
                "/api/projects/%s/labels".formatted(projectId),
                """
                {
                  "name": "Hidden"
                }
                """,
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());
    }

    @Test
    void issueLabelsCanBeCreatedFilteredReplacedAndCleared() throws Exception {
        AuthSession owner = register("issue-label-owner+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Issue Labels Project").get("id").asText();
        String otherProjectId = createProject(owner, "Other Labels Project").get("id").asText();
        String bugLabelId = createProjectLabel(owner, projectId, "Bug", "#ef4444").get("id").asText();
        String frontendLabelId = createProjectLabel(owner, projectId, "Frontend", "#0ea5e9").get("id").asText();
        String otherProjectLabelId = createProjectLabel(owner, otherProjectId, "External", "#22c55e").get("id").asText();

        JsonNode issue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Labelled issue",
                  "labelIds": ["%s", "%s"]
                }
                """.formatted(projectId, bugLabelId, frontendLabelId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0].name").value("Bug"))
                .andExpect(jsonPath("$.labels[1].name").value("Frontend"))
                .andReturnJson();
        String issueId = issue.get("id").asText();

        createIssue(owner, projectId, "Unlabelled issue", "No labels", "TODO", "LOW");

        JsonNode bugIssues = getIssues(owner, projectId, null, null, null, null, bugLabelId)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(bugIssues)).containsExactly("Labelled issue");

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "labelIds": ["%s"]
                }
                """.formatted(frontendLabelId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0].id").value(frontendLabelId))
                .andExpect(jsonPath("$.labels[1]").doesNotExist());

        JsonNode clearedIssue = patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "labelIds": []
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
        assertThat(clearedIssue.get("labels").isEmpty()).isTrue();

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Cross project label",
                  "labelIds": ["%s"]
                }
                """.formatted(projectId, otherProjectLabelId),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "labelIds": ["%s"]
                }
                """.formatted(UUID.randomUUID()),
                owner.accessToken()
        ).andExpect(status().isNotFound());
    }

    @Test
    void projectWorkflowStatesAreCreatedListedAndPermissioned() throws Exception {
        AuthSession owner = register("workflow-owner+" + uniqueId() + "@example.com");
        AuthSession projectMember = createWorkspaceMember(owner, "workflow-member+" + uniqueId() + "@example.com");
        AuthSession nonProjectMember = createWorkspaceMember(owner, "workflow-non-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Workflow Project").get("id").asText();
        addProjectMember(owner, projectId, userId(projectMember));

        mockMvc.perform(get("/api/projects/{projectId}/workflow-states", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Todo"))
                .andExpect(jsonPath("$[0].category").value("TODO"))
                .andExpect(jsonPath("$[1].name").value("In progress"))
                .andExpect(jsonPath("$[1].category").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[2].name").value("Done"))
                .andExpect(jsonPath("$[2].category").value("DONE"));

        mockMvc.perform(get("/api/projects/{projectId}/workflow-states", projectId)
                        .header("Authorization", bearer(projectMember.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Todo"));

        postJson(
                "/api/projects/%s/workflow-states".formatted(projectId),
                """
                {
                  "name": "Review",
                  "category": "IN_PROGRESS"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.name").value("Review"))
                .andExpect(jsonPath("$.category").value("IN_PROGRESS"));

        postJson(
                "/api/projects/%s/workflow-states".formatted(projectId),
                """
                {
                  "name": "review",
                  "category": "IN_PROGRESS"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isConflict());

        postJson(
                "/api/projects/%s/workflow-states".formatted(projectId),
                """
                {
                  "name": "QA",
                  "category": "IN_PROGRESS"
                }
                """,
                projectMember.accessToken()
        ).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/projects/{projectId}/workflow-states", projectId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        postJson(
                "/api/projects/%s/workflow-states".formatted(projectId),
                """
                {
                  "name": "Hidden",
                  "category": "IN_PROGRESS"
                }
                """,
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());

        assertThat(projectWorkflowStateRepository.findAll())
                .extracting(workflowState -> workflowState.getName())
                .contains("Todo", "In progress", "Done", "Review");
    }

    @Test
    void issueWorkflowStateCanBeSpecifiedFilteredMovedAndArchived() throws Exception {
        AuthSession owner = register("issue-workflow-owner+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Issue Workflow Project").get("id").asText();
        String otherProjectId = createProject(owner, "Other Workflow Project").get("id").asText();
        String reviewStateId = createProjectWorkflowState(owner, projectId, "Review", "IN_PROGRESS")
                .get("id")
                .asText();
        String otherProjectStateId = workflowStates(owner, otherProjectId).get(0).get("id").asText();

        JsonNode defaultIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Default workflow issue"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.workflowState.name").value("Todo"))
                .andReturnJson();

        JsonNode reviewIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Review workflow issue",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, reviewStateId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.workflowState.id").value(reviewStateId))
                .andExpect(jsonPath("$.workflowState.name").value("Review"))
                .andReturnJson();

        JsonNode reviewIssues = getIssues(owner, projectId, null, null, null, null, null, reviewStateId)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(reviewIssues)).containsExactly("Review workflow issue");

        String defaultIssueId = defaultIssue.get("id").asText();
        patchJson(
                "/api/issues/%s".formatted(defaultIssueId),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(reviewStateId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.workflowState.name").value("Review"));

        mockMvc.perform(get("/api/issues/{issueId}/activities", defaultIssueId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("ISSUE_STATUS_CHANGED"))
                .andExpect(jsonPath("$.items[0].metadata.fromStatus").value("Todo"))
                .andExpect(jsonPath("$.items[0].metadata.toStatus").value("Review"))
                .andExpect(jsonPath("$.items[0].metadata.toWorkflowStateId").value(reviewStateId))
                .andExpect(jsonPath("$.items[1].eventType").value("ISSUE_CREATED"));

        patchJson(
                "/api/issues/%s".formatted(reviewIssue.get("id").asText()),
                """
                {
                  "status": "ARCHIVED"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.workflowState.name").value("Review"))
                .andExpect(jsonPath("$.archivedAt").isString());

        JsonNode activeIssues = getIssues(owner, projectId)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(activeIssues)).containsExactly("Default workflow issue");

        JsonNode archivedIssues = getIssues(owner, projectId, "ARCHIVED", null, null)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(archivedIssues)).containsExactly("Review workflow issue");

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Cross project workflow state",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, otherProjectStateId),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        patchJson(
                "/api/issues/%s".formatted(defaultIssueId),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(UUID.randomUUID()),
                owner.accessToken()
        ).andExpect(status().isNotFound());
    }

    @Test
    void projectOwnerCanUpdateWorkflowStateAndIssueStatusFollowsCategory() throws Exception {
        AuthSession owner = register("workflow-update-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "workflow-update-member+" + uniqueId() + "@example.com");
        AuthSession nonProjectMember = createWorkspaceMember(
                owner,
                "workflow-update-non-member+" + uniqueId() + "@example.com"
        );
        String projectId = createProject(owner, "Workflow Update Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));
        String todoStateId = workflowStateIdByName(workflowStates(owner, projectId), "Todo");

        JsonNode issue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Category follows workflow state"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturnJson();

        patchJson(
                "/api/projects/%s/workflow-states/%s".formatted(projectId, todoStateId),
                """
                {
                  "name": "Inbox",
                  "category": "DONE"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(todoStateId))
                .andExpect(jsonPath("$.name").value("Inbox"))
                .andExpect(jsonPath("$.category").value("DONE"));

        mockMvc.perform(get("/api/issues/{issueId}", issue.get("id").asText())
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.workflowState.id").value(todoStateId))
                .andExpect(jsonPath("$.workflowState.name").value("Inbox"))
                .andExpect(jsonPath("$.workflowState.category").value("DONE"));

        patchJson(
                "/api/projects/%s/workflow-states/%s".formatted(projectId, todoStateId),
                """
                {
                  "name": "Done",
                  "category": "DONE"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isConflict());

        patchJson(
                "/api/projects/%s/workflow-states/%s".formatted(projectId, todoStateId),
                """
                {
                  "name": "Member edit",
                  "category": "IN_PROGRESS"
                }
                """,
                member.accessToken()
        ).andExpect(status().isForbidden());

        patchJson(
                "/api/projects/%s/workflow-states/%s".formatted(projectId, todoStateId),
                """
                {
                  "name": "Hidden edit",
                  "category": "IN_PROGRESS"
                }
                """,
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());
    }

    @Test
    void projectOwnerCanReorderWorkflowStatesWithValidation() throws Exception {
        AuthSession owner = register("workflow-reorder-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "workflow-reorder-member+" + uniqueId() + "@example.com");
        AuthSession nonProjectMember = createWorkspaceMember(
                owner,
                "workflow-reorder-non-member+" + uniqueId() + "@example.com"
        );
        String projectId = createProject(owner, "Workflow Reorder Project").get("id").asText();
        String otherProjectId = createProject(owner, "Other Workflow Reorder Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));
        String reviewStateId = createProjectWorkflowState(owner, projectId, "Review", "IN_PROGRESS")
                .get("id")
                .asText();
        JsonNode workflowStates = workflowStates(owner, projectId);
        String todoStateId = workflowStateIdByName(workflowStates, "Todo");
        String inProgressStateId = workflowStateIdByName(workflowStates, "In progress");
        String doneStateId = workflowStateIdByName(workflowStates, "Done");
        String otherProjectStateId = workflowStates(owner, otherProjectId).get(0).get("id").asText();

        String reorderedIds = """
                ["%s", "%s", "%s", "%s"]
                """.formatted(doneStateId, reviewStateId, inProgressStateId, todoStateId);
        patchJson(
                "/api/projects/%s/workflow-states/order".formatted(projectId),
                """
                {
                  "workflowStateIds": %s
                }
                """.formatted(reorderedIds),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(doneStateId))
                .andExpect(jsonPath("$[0].position").value(10000))
                .andExpect(jsonPath("$[1].id").value(reviewStateId))
                .andExpect(jsonPath("$[1].position").value(20000))
                .andExpect(jsonPath("$[2].id").value(inProgressStateId))
                .andExpect(jsonPath("$[2].position").value(30000))
                .andExpect(jsonPath("$[3].id").value(todoStateId))
                .andExpect(jsonPath("$[3].position").value(40000));

        mockMvc.perform(get("/api/projects/{projectId}/workflow-states", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Done"))
                .andExpect(jsonPath("$[1].name").value("Review"))
                .andExpect(jsonPath("$[2].name").value("In progress"))
                .andExpect(jsonPath("$[3].name").value("Todo"));

        patchJson(
                "/api/projects/%s/workflow-states/order".formatted(projectId),
                """
                {
                  "workflowStateIds": %s
                }
                """.formatted(reorderedIds),
                member.accessToken()
        ).andExpect(status().isForbidden());

        patchJson(
                "/api/projects/%s/workflow-states/order".formatted(projectId),
                """
                {
                  "workflowStateIds": %s
                }
                """.formatted(reorderedIds),
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());

        patchJson(
                "/api/projects/%s/workflow-states/order".formatted(projectId),
                """
                {
                  "workflowStateIds": ["%s", "%s", "%s", "%s"]
                }
                """.formatted(doneStateId, doneStateId, inProgressStateId, todoStateId),
                owner.accessToken()
        ).andExpect(status().isBadRequest());

        patchJson(
                "/api/projects/%s/workflow-states/order".formatted(projectId),
                """
                {
                  "workflowStateIds": ["%s", "%s", "%s"]
                }
                """.formatted(doneStateId, reviewStateId, inProgressStateId),
                owner.accessToken()
        ).andExpect(status().isBadRequest());

        patchJson(
                "/api/projects/%s/workflow-states/order".formatted(projectId),
                """
                {
                  "workflowStateIds": ["%s", "%s", "%s", "%s"]
                }
                """.formatted(doneStateId, reviewStateId, inProgressStateId, otherProjectStateId),
                owner.accessToken()
        ).andExpect(status().isNotFound());
    }

    @Test
    void boardReturnsOrderedColumnsAndAppendsNewIssues() throws Exception {
        AuthSession owner = register("board-owner+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Board Project").get("id").asText();
        JsonNode workflowStates = workflowStates(owner, projectId);
        String todoStateId = workflowStateIdByName(workflowStates, "Todo");
        String doneStateId = workflowStateIdByName(workflowStates, "Done");

        JsonNode firstIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "First board issue"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(10000))
                .andReturnJson();
        JsonNode secondIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Second board issue"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(20000))
                .andReturnJson();
        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Done board issue",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, doneStateId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(10000));

        mockMvc.perform(get("/api/issues/board")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.columns[0].workflowState.id").value(todoStateId))
                .andExpect(jsonPath("$.columns[0].issues[0].title").value("First board issue"))
                .andExpect(jsonPath("$.columns[0].issues[0].boardPosition").value(10000))
                .andExpect(jsonPath("$.columns[0].issues[1].title").value("Second board issue"))
                .andExpect(jsonPath("$.columns[0].issues[1].boardPosition").value(20000))
                .andExpect(jsonPath("$.columns[1].workflowState.name").value("In progress"))
                .andExpect(jsonPath("$.columns[1].issues").isEmpty())
                .andExpect(jsonPath("$.columns[2].workflowState.id").value(doneStateId))
                .andExpect(jsonPath("$.columns[2].issues[0].title").value("Done board issue"));

        JsonNode listIssues = getIssues(owner, projectId)
                .andExpect(status().isOk())
                .andReturnJson();
        assertThat(issueTitles(listIssues))
                .containsExactly("Done board issue", "Second board issue", "First board issue");

        patchJson(
                "/api/issues/%s".formatted(secondIssue.get("id").asText()),
                """
                {
                  "status": "ARCHIVED"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk());

        mockMvc.perform(get("/api/issues/board")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].issues.length()").value(1))
                .andExpect(jsonPath("$.columns[0].issues[0].id").value(firstIssue.get("id").asText()));
    }

    @Test
    void projectMemberCanMoveIssueStateToColumnEndWithoutDuplicateActivity() throws Exception {
        AuthSession owner = register("board-state-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "board-state-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Board State Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));
        JsonNode workflowStates = workflowStates(owner, projectId);
        String inProgressStateId = workflowStateIdByName(workflowStates, "In progress");

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Existing in progress",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, inProgressStateId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(10000));
        JsonNode movedIssue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Move to in progress"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
        String movedIssueId = movedIssue.get("id").asText();

        patchJson(
                "/api/issues/%s/state".formatted(movedIssueId),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(inProgressStateId),
                member.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowState.id").value(inProgressStateId))
                .andExpect(jsonPath("$.boardPosition").value(20000));

        patchJson(
                "/api/issues/%s/state".formatted(movedIssueId),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(inProgressStateId),
                member.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(20000));

        mockMvc.perform(get("/api/issues/{issueId}/activities", movedIssueId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("ISSUE_STATUS_CHANGED"))
                .andExpect(jsonPath("$.items[0].metadata.toWorkflowStateId").value(inProgressStateId))
                .andExpect(jsonPath("$.items[1].eventType").value("ISSUE_CREATED"));
    }

    @Test
    void issueReorderSupportsSameColumnAndAtomicCrossColumnMove() throws Exception {
        AuthSession owner = register("board-reorder-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "board-reorder-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Board Reorder Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));
        JsonNode workflowStates = workflowStates(owner, projectId);
        String todoStateId = workflowStateIdByName(workflowStates, "Todo");
        String inProgressStateId = workflowStateIdByName(workflowStates, "In progress");
        JsonNode issueA = createIssue(owner, projectId, "Board A", "A", "TODO", "LOW");
        JsonNode issueB = createIssue(owner, projectId, "Board B", "B", "TODO", "LOW");
        JsonNode issueC = createIssue(owner, projectId, "Board C", "C", "TODO", "LOW");
        JsonNode issueD = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Board D",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, inProgressStateId),
                member.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();

        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s", "%s", "%s"]
                }
                """.formatted(
                        issueB.get("id").asText(),
                        todoStateId,
                        issueB.get("id").asText(),
                        issueA.get("id").asText(),
                        issueC.get("id").asText()
                ),
                member.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].issues[0].id").value(issueB.get("id").asText()))
                .andExpect(jsonPath("$.columns[0].issues[0].boardPosition").value(10000))
                .andExpect(jsonPath("$.columns[0].issues[1].id").value(issueA.get("id").asText()))
                .andExpect(jsonPath("$.columns[0].issues[1].boardPosition").value(20000))
                .andExpect(jsonPath("$.columns[0].issues[2].id").value(issueC.get("id").asText()))
                .andExpect(jsonPath("$.columns[0].issues[2].boardPosition").value(30000));

        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s", "%s"]
                }
                """.formatted(
                        issueC.get("id").asText(),
                        inProgressStateId,
                        issueC.get("id").asText(),
                        issueD.get("id").asText()
                ),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].issues.length()").value(2))
                .andExpect(jsonPath("$.columns[0].issues[0].id").value(issueB.get("id").asText()))
                .andExpect(jsonPath("$.columns[0].issues[1].id").value(issueA.get("id").asText()))
                .andExpect(jsonPath("$.columns[1].issues[0].id").value(issueC.get("id").asText()))
                .andExpect(jsonPath("$.columns[1].issues[0].workflowState.id").value(inProgressStateId))
                .andExpect(jsonPath("$.columns[1].issues[0].boardPosition").value(10000))
                .andExpect(jsonPath("$.columns[1].issues[1].id").value(issueD.get("id").asText()))
                .andExpect(jsonPath("$.columns[1].issues[1].boardPosition").value(20000));

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueB.get("id").asText())
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/issues/{issueId}/activities", issueC.get("id").asText())
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("ISSUE_STATUS_CHANGED"));
    }

    @Test
    void boardMovementValidatesAccessPayloadAndArchivedIssues() throws Exception {
        AuthSession owner = register("board-validation-owner+" + uniqueId() + "@example.com");
        AuthSession nonProjectMember = createWorkspaceMember(
                owner,
                "board-validation-member+" + uniqueId() + "@example.com"
        );
        AuthSession outsider = register("board-validation-outsider+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Board Validation Project").get("id").asText();
        String otherProjectId = createProject(owner, "Other Board Validation Project").get("id").asText();
        JsonNode workflowStates = workflowStates(owner, projectId);
        String todoStateId = workflowStateIdByName(workflowStates, "Todo");
        String otherProjectStateId = workflowStateIdByName(workflowStates(owner, otherProjectId), "Todo");
        JsonNode issueA = createIssue(owner, projectId, "Validation A", "A", "TODO", "LOW");
        JsonNode issueB = createIssue(owner, projectId, "Validation B", "B", "TODO", "LOW");
        JsonNode otherIssue = createIssue(owner, otherProjectId, "Other validation", "Other", "TODO", "LOW");

        mockMvc.perform(get("/api/issues/board")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/issues/board")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(outsider.accessToken())))
                .andExpect(status().isNotFound());

        patchJson(
                "/api/issues/%s/state".formatted(issueA.get("id").asText()),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(todoStateId),
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());
        patchJson(
                "/api/issues/%s/state".formatted(issueA.get("id").asText()),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(todoStateId),
                outsider.accessToken()
        ).andExpect(status().isNotFound());
        patchJson(
                "/api/issues/%s/state".formatted(issueA.get("id").asText()),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(otherProjectStateId),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s", "%s"]
                }
                """.formatted(
                        issueA.get("id").asText(),
                        todoStateId,
                        issueA.get("id").asText(),
                        issueB.get("id").asText()
                ),
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());

        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s", "%s"]
                }
                """.formatted(
                        issueA.get("id").asText(),
                        todoStateId,
                        issueA.get("id").asText(),
                        issueA.get("id").asText()
                ),
                owner.accessToken()
        ).andExpect(status().isBadRequest());
        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s"]
                }
                """.formatted(issueA.get("id").asText(), todoStateId, issueA.get("id").asText()),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].issues[0].id").value(issueA.get("id").asText()));
        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s", "%s", "%s"]
                }
                """.formatted(
                        issueA.get("id").asText(),
                        todoStateId,
                        issueA.get("id").asText(),
                        issueB.get("id").asText(),
                        otherIssue.get("id").asText()
                ),
                owner.accessToken()
        ).andExpect(status().isNotFound());
        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s", "%s", "%s"]
                }
                """.formatted(
                        issueA.get("id").asText(),
                        todoStateId,
                        issueA.get("id").asText(),
                        issueB.get("id").asText(),
                        UUID.randomUUID()
                ),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        patchJson(
                "/api/issues/%s".formatted(issueA.get("id").asText()),
                """
                {
                  "status": "ARCHIVED"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk());
        patchJson(
                "/api/issues/%s/state".formatted(issueA.get("id").asText()),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(todoStateId),
                owner.accessToken()
        ).andExpect(status().isConflict());
        patchJson(
                "/api/issues/reorder",
                """
                {
                  "issueId": "%s",
                  "workflowStateId": "%s",
                  "orderedIssueIds": ["%s", "%s"]
                }
                """.formatted(
                        issueA.get("id").asText(),
                        todoStateId,
                        issueA.get("id").asText(),
                        issueB.get("id").asText()
                ),
                owner.accessToken()
        ).andExpect(status().isConflict());
    }

    @Test
    void genericIssuePatchAppendsOnStateChangeAndUnarchive() throws Exception {
        AuthSession owner = register("board-compat-owner+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Board Compatibility Project").get("id").asText();
        String inProgressStateId = workflowStateIdByName(workflowStates(owner, projectId), "In progress");
        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Existing target issue",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, inProgressStateId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(10000));
        JsonNode movedIssue = createIssue(owner, projectId, "Generic patch move", "Move", "TODO", "LOW");
        String movedIssueId = movedIssue.get("id").asText();

        patchJson(
                "/api/issues/%s".formatted(movedIssueId),
                """
                {
                  "workflowStateId": "%s"
                }
                """.formatted(inProgressStateId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(20000));

        patchJson(
                "/api/issues/%s".formatted(movedIssueId),
                """
                {
                  "status": "ARCHIVED"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(20000));
        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "New target issue",
                  "workflowStateId": "%s"
                }
                """.formatted(projectId, inProgressStateId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardPosition").value(20000));

        patchJson(
                "/api/issues/%s".formatted(movedIssueId),
                """
                {
                  "status": "IN_PROGRESS"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(jsonPath("$.boardPosition").value(30000));

        mockMvc.perform(get("/api/issues/board")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[1].issues[0].title").value("Existing target issue"))
                .andExpect(jsonPath("$.columns[1].issues[1].title").value("New target issue"))
                .andExpect(jsonPath("$.columns[1].issues[2].id").value(movedIssueId));
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

    @Test
    void rejectsSameWorkspaceAccessForNonProjectMember() throws Exception {
        AuthSession owner = register("same-workspace-owner+" + uniqueId() + "@example.com");
        AuthSession nonProjectMember = createWorkspaceMember(
                owner,
                "same-workspace-member+" + uniqueId() + "@example.com"
        );

        JsonNode project = postJson(
                "/api/projects",
                """
                {
                  "name": "Project Members Only"
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
                  "title": "Private project issue"
                }
                """.formatted(projectId),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
        String issueId = issue.get("id").asText();

        mockMvc.perform(get("/api/projects")
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/projects/{projectId}", projectId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/projects/{projectId}/members", projectId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(nonProjectMember)),
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Cross project write"
                }
                """.formatted(projectId),
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/issues/{issueId}", issueId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        postJson(
                "/api/issues/%s/comments".formatted(issueId),
                """
                {
                  "body": "I should not be able to comment."
                }
                """,
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(nonProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "status": "DONE"
                }
                """,
                nonProjectMember.accessToken()
        ).andExpect(status().isNotFound());
    }

    @Test
    void projectOwnerCanReadDetailsListMembersAndAddWorkspaceMember() throws Exception {
        AuthSession owner = register("project-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(
                owner,
                "project-member+" + uniqueId() + "@example.com"
        );
        AuthSession anotherWorkspaceMember = createWorkspaceMember(
                owner,
                "project-another-member+" + uniqueId() + "@example.com"
        );

        JsonNode project = createProject(owner, "Member Managed Project");
        String projectId = project.get("id").asText();

        mockMvc.perform(get("/api/projects/{projectId}", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId))
                .andExpect(jsonPath("$.name").value("Member Managed Project"));

        mockMvc.perform(get("/api/projects/{projectId}/members", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(userId(owner)))
                .andExpect(jsonPath("$[0].email").value(owner.email()))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(member)),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId(member)))
                .andExpect(jsonPath("$.email").value(member.email()))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/projects/{projectId}/members", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].userId").value(userId(member)))
                .andExpect(jsonPath("$[1].role").value("MEMBER"));

        mockMvc.perform(get("/api/projects")
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(projectId));

        JsonNode issue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Member can work here"
                }
                """.formatted(projectId),
                member.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Member can work here"))
                .andReturnJson();

        mockMvc.perform(get("/api/issues/{issueId}", issue.get("id").asText())
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(issue.get("id").asText()));

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(member)),
                owner.accessToken()
        ).andExpect(status().isConflict());

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "OWNER"
                }
                """.formatted(userId(anotherWorkspaceMember)),
                owner.accessToken()
        ).andExpect(status().isBadRequest());
    }

    @Test
    void projectMemberCannotAddOtherMembers() throws Exception {
        AuthSession owner = register("member-permission-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "member-permission-member+" + uniqueId() + "@example.com");
        AuthSession target = createWorkspaceMember(owner, "member-permission-target+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Member Permission Project").get("id").asText();

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(member)),
                owner.accessToken()
        ).andExpect(status().isOk());

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(target)),
                member.accessToken()
        ).andExpect(status().isForbidden());
    }

    @Test
    void addingProjectMemberRequiresActiveWorkspaceMember() throws Exception {
        AuthSession owner = register("workspace-member-owner+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Workspace Member Required Project").get("id").asText();
        User externalUser = userRepository.save(new User(
                "external-user+" + uniqueId() + "@example.com",
                "unused",
                "External User"
        ));
        String disabledWorkspaceMemberId = createDisabledWorkspaceMember(
                owner,
                "disabled-workspace-member+" + uniqueId() + "@example.com"
        );

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(externalUser.getId()),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(disabledWorkspaceMemberId),
                owner.accessToken()
        ).andExpect(status().isNotFound());
    }

    @Test
    void projectOwnerCanUpdateMemberRolesAndSelfDemoteWhenAnotherOwnerExists() throws Exception {
        AuthSession owner = register("role-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "role-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Role Managed Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));
        String ownerMemberId = projectMemberId(owner, projectId, userId(owner));
        String memberId = projectMemberId(owner, projectId, userId(member));

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, memberId),
                """
                {
                  "role": "OWNER"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, ownerMemberId),
                """
                {
                  "role": "MEMBER"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, ownerMemberId),
                """
                {
                  "role": "OWNER"
                }
                """,
                member.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void projectMemberManagementRequiresOwnerAndHidesCrossProjectMemberships() throws Exception {
        AuthSession owner = register("manage-permission-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "manage-permission-member+" + uniqueId() + "@example.com");
        AuthSession otherProjectMember = createWorkspaceMember(
                owner,
                "manage-permission-other+" + uniqueId() + "@example.com"
        );
        String projectId = createProject(owner, "Managed Project").get("id").asText();
        String otherProjectId = createProject(owner, "Other Managed Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));
        addProjectMember(owner, otherProjectId, userId(otherProjectMember));
        String ownerMemberId = projectMemberId(owner, projectId, userId(owner));
        String crossProjectMemberId = projectMemberId(owner, otherProjectId, userId(otherProjectMember));

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, ownerMemberId),
                """
                {
                  "role": "MEMBER"
                }
                """,
                member.accessToken()
        ).andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberId}", projectId, ownerMemberId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isForbidden());

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, ownerMemberId),
                """
                {
                  "role": "MEMBER"
                }
                """,
                otherProjectMember.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberId}", projectId, ownerMemberId)
                        .header("Authorization", bearer(otherProjectMember.accessToken())))
                .andExpect(status().isNotFound());

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, crossProjectMemberId),
                """
                {
                  "role": "OWNER"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(delete(
                        "/api/projects/{projectId}/members/{memberId}",
                        projectId,
                        crossProjectMemberId
                ).header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void lastActiveProjectOwnerCannotBeDemotedOrRemoved() throws Exception {
        AuthSession owner = register("last-owner+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Last Owner Project").get("id").asText();
        String ownerMemberId = projectMemberId(owner, projectId, userId(owner));

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, ownerMemberId),
                """
                {
                  "role": "MEMBER"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isConflict());

        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberId}", projectId, ownerMemberId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/projects/{projectId}/members", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void removedProjectMemberLosesAccessAndCanBeReactivated() throws Exception {
        AuthSession owner = register("remove-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "remove-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Reactivation Project").get("id").asText();
        JsonNode addedMember = postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(member)),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
        String memberId = addedMember.get("id").asText();
        Instant firstJoinedAt = Instant.parse(addedMember.get("joinedAt").asText());

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, memberId),
                """
                {
                  "role": "OWNER"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isOk());

        JsonNode issue = postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "Keep historical assignee",
                  "assigneeUserId": "%s"
                }
                """.formatted(projectId, userId(member)),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
        String issueId = issue.get("id").asText();

        postJson(
                "/api/issues/%s/comments".formatted(issueId),
                """
                {
                  "body": "Comment before removal"
                }
                """,
                member.accessToken()
        ).andExpect(status().isOk());

        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberId}", projectId, memberId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberId}", projectId, memberId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/projects/{projectId}/members", projectId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value(memberId))
                .andExpect(jsonPath("$[1].status").value("DISABLED"));

        patchJson(
                "/api/projects/%s/members/%s".formatted(projectId, memberId),
                """
                {
                  "role": "MEMBER"
                }
                """,
                owner.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/issues/{issueId}", issueId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.id").value(userId(member)));

        mockMvc.perform(get("/api/projects")
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/projects/{projectId}", projectId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/{projectId}/members", projectId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "No longer allowed"
                }
                """.formatted(projectId),
                member.accessToken()
        ).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/issues/{issueId}", issueId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        patchJson(
                "/api/issues/%s".formatted(issueId),
                """
                {
                  "title": "No longer editable"
                }
                """,
                member.accessToken()
        ).andExpect(status().isNotFound());
        postJson(
                "/api/issues/%s/comments".formatted(issueId),
                """
                {
                  "body": "No longer allowed"
                }
                """,
                member.accessToken()
        ).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isNotFound());

        JsonNode reactivatedMember = postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(member)),
                owner.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(memberId))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturnJson();
        assertThat(Instant.parse(reactivatedMember.get("joinedAt").asText()))
                .isAfterOrEqualTo(firstJoinedAt);

        mockMvc.perform(get("/api/projects/{projectId}", projectId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/issues/{issueId}", issueId)
                        .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isOk());

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(member)),
                owner.accessToken()
        ).andExpect(status().isConflict());
    }

    @Test
    void disabledWorkspaceMemberCannotBeReactivatedInProject() throws Exception {
        AuthSession owner = register("reactivate-owner+" + uniqueId() + "@example.com");
        AuthSession member = createWorkspaceMember(owner, "reactivate-member+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Disabled Reactivation Project").get("id").asText();
        addProjectMember(owner, projectId, userId(member));
        String memberId = projectMemberId(owner, projectId, userId(member));

        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberId}", projectId, memberId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());

        WorkspaceMembership workspaceMembership = membershipRepository.findByWorkspace_IdAndUser_Id(
                        workspaceId(owner),
                        UUID.fromString(userId(member))
                )
                .orElseThrow();
        workspaceMembership.disable();
        membershipRepository.save(workspaceMembership);

        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId(member)),
                owner.accessToken()
        ).andExpect(status().isNotFound());

        assertThat(projectMemberRepository.findById(UUID.fromString(memberId)))
                .get()
                .extracting(memberRecord -> memberRecord.getStatus())
                .isEqualTo(MembershipStatus.DISABLED);
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

    private void addProjectMember(AuthSession owner, String projectId, String userId) throws Exception {
        postJson(
                "/api/projects/%s/members".formatted(projectId),
                """
                {
                  "userId": "%s",
                  "role": "MEMBER"
                }
                """.formatted(userId),
                owner.accessToken()
        ).andExpect(status().isOk());
    }

    private String projectMemberId(AuthSession owner, String projectId, String userId) {
        return projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_Id(
                        workspaceId(owner),
                        UUID.fromString(projectId),
                        UUID.fromString(userId)
                )
                .orElseThrow()
                .getId()
                .toString();
    }

    private JsonNode createProjectLabel(AuthSession session, String projectId, String name, String color) throws Exception {
        return postJson(
                "/api/projects/%s/labels".formatted(projectId),
                """
                {
                  "name": "%s",
                  "color": "%s"
                }
                """.formatted(name, color),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
    }

    private JsonNode createProjectWorkflowState(
            AuthSession session,
            String projectId,
            String name,
            String category
    ) throws Exception {
        return postJson(
                "/api/projects/%s/workflow-states".formatted(projectId),
                """
                {
                  "name": "%s",
                  "category": "%s"
                }
                """.formatted(name, category),
                session.accessToken()
        ).andExpect(status().isOk())
                .andReturnJson();
    }

    private JsonNode workflowStates(AuthSession session, String projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{projectId}/workflow-states", projectId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String workflowStateIdByName(JsonNode workflowStates, String name) {
        for (JsonNode workflowState : workflowStates) {
            if (workflowState.get("name").asText().equals(name)) {
                return workflowState.get("id").asText();
            }
        }

        throw new AssertionError("Workflow state not found: " + name);
    }

    private AuthSession createWorkspaceMember(AuthSession ownerSession, String email) {
        User owner = userRepository.findByEmail(ownerSession.email()).orElseThrow();
        Workspace workspace = workspaceRepository.findFirstByOwner_IdOrderByCreatedAtAsc(owner.getId()).orElseThrow();
        User user = userRepository.save(new User(email, "unused", "Workspace Member"));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
                workspace,
                user,
                WorkspaceRole.MEMBER
        ));
        String accessToken = jwtService.generateAccessToken(user, membership);
        return new AuthSession(accessToken, email);
    }

    private String createDisabledWorkspaceMember(AuthSession ownerSession, String email) {
        User owner = userRepository.findByEmail(ownerSession.email()).orElseThrow();
        Workspace workspace = workspaceRepository.findFirstByOwner_IdOrderByCreatedAtAsc(owner.getId()).orElseThrow();
        User user = userRepository.save(new User(email, "unused", "Disabled Workspace Member"));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
                workspace,
                user,
                WorkspaceRole.MEMBER
        ));
        membership.disable();
        membershipRepository.save(membership);
        return user.getId().toString();
    }

    private String userId(AuthSession session) {
        return userRepository.findByEmail(session.email()).orElseThrow().getId().toString();
    }

    private UUID workspaceId(AuthSession session) {
        User owner = userRepository.findByEmail(session.email()).orElseThrow();
        return workspaceRepository.findFirstByOwner_IdOrderByCreatedAtAsc(owner.getId()).orElseThrow().getId();
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
        return getIssues(session, projectId, null, null, null, null);
    }

    private ResultActionsWithJson getIssues(
            AuthSession session,
            String projectId,
            String status,
            String priority,
            String query
    ) throws Exception {
        return getIssues(session, projectId, status, priority, query, null);
    }

    private ResultActionsWithJson getIssues(
            AuthSession session,
            String projectId,
            String status,
            String priority,
            String query,
            String assigneeUserId
    ) throws Exception {
        return getIssues(session, projectId, status, priority, query, assigneeUserId, null);
    }

    private ResultActionsWithJson getIssues(
            AuthSession session,
            String projectId,
            String status,
            String priority,
            String query,
            String assigneeUserId,
            String labelId
    ) throws Exception {
        return getIssues(session, projectId, status, priority, query, assigneeUserId, labelId, null);
    }

    private ResultActionsWithJson getIssues(
            AuthSession session,
            String projectId,
            String status,
            String priority,
            String query,
            String assigneeUserId,
            String labelId,
            String workflowStateId
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
        if (assigneeUserId != null) {
            request.queryParam("assigneeUserId", assigneeUserId);
        }
        if (labelId != null) {
            request.queryParam("labelId", labelId);
        }
        if (workflowStateId != null) {
            request.queryParam("workflowStateId", workflowStateId);
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
        issues.get("items").forEach(issue -> titles.add(issue.get("title").asText()));
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
