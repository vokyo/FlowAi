package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AnalyticsIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void emptyOverviewHasStableShapeAndProjectMembershipIsolation() throws Exception {
        Session owner = register("analytics-owner+" + uniqueId() + "@example.com");
        JsonNode project = createProject(owner, "Empty analytics");
        String projectId = project.get("id").asText();
        ProjectMemberSession member = createProjectMember(owner, projectId, "Analytics member");
        Session outsider = register("analytics-outsider+" + uniqueId() + "@example.com");

        getAnalytics(projectId, null, owner.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.rangeDays").value(30))
                .andExpect(jsonPath("$.totalIssues").value(0))
                .andExpect(jsonPath("$.completedIssues").value(0))
                .andExpect(jsonPath("$.completionRate").value(0.0))
                .andExpect(jsonPath("$.archivedIssues").value(0))
                .andExpect(jsonPath("$.statusDistribution.length()").value(3))
                .andExpect(jsonPath("$.statusDistribution[0].category").value("TODO"))
                .andExpect(jsonPath("$.statusDistribution[0].count").value(0))
                .andExpect(jsonPath("$.statusDistribution[1].category").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.statusDistribution[2].category").value("DONE"))
                .andExpect(jsonPath("$.assigneeDistribution.length()").value(0))
                .andExpect(jsonPath("$.completionTrend.length()").value(30));

        getAnalytics(projectId, 7, member.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rangeDays").value(7))
                .andExpect(jsonPath("$.completionTrend.length()").value(7));
        getAnalytics(projectId, 90, owner.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionTrend.length()").value(90));
        getAnalytics(projectId, 8, owner.accessToken())
                .andExpect(status().isBadRequest());
        getAnalytics(projectId, 30, outsider.accessToken())
                .andExpect(status().isNotFound());

        ProjectMember projectMembership = projectMemberRepository.findById(member.projectMemberId()).orElseThrow();
        projectMembership.disable();
        projectMemberRepository.saveAndFlush(projectMembership);
        getAnalytics(projectId, 30, member.accessToken())
                .andExpect(status().isNotFound());
    }

    @Test
    void overviewAggregatesStatusesAssigneesArchivedAndCompletionTrend() throws Exception {
        Session owner = register("analytics-count-owner+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Analytics counts").get("id").asText();
        ProjectMemberSession assignee = createProjectMember(owner, projectId, "Assigned person");
        JsonNode workflowStates = listWorkflowStates(owner, projectId);
        String todoId = workflowStateId(workflowStates, "Todo");
        String inProgressId = workflowStateId(workflowStates, "In progress");
        String doneId = workflowStateId(workflowStates, "Done");

        createIssue(owner, projectId, "Todo unassigned", todoId, null);
        createIssue(owner, projectId, "In progress assigned", inProgressId, assignee.userId());
        createIssue(owner, projectId, "Done assigned", doneId, assignee.userId());
        JsonNode archived = createIssue(owner, projectId, "Archived done", doneId, null);
        patchIssue(archived.get("id").asText(), "{ \"status\": \"ARCHIVED\" }", owner.accessToken())
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/projects/{projectId}/members/{memberId}", projectId, assignee.projectMemberId())
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        getAnalytics(projectId, 7, owner.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIssues").value(3))
                .andExpect(jsonPath("$.completedIssues").value(1))
                .andExpect(jsonPath("$.completionRate").value(1.0 / 3.0))
                .andExpect(jsonPath("$.archivedIssues").value(1))
                .andExpect(jsonPath("$.statusDistribution[0].count").value(1))
                .andExpect(jsonPath("$.statusDistribution[1].count").value(1))
                .andExpect(jsonPath("$.statusDistribution[2].count").value(1))
                .andExpect(jsonPath("$.assigneeDistribution[0].userId").value(assignee.userId()))
                .andExpect(jsonPath("$.assigneeDistribution[0].displayName").value("Assigned person"))
                .andExpect(jsonPath("$.assigneeDistribution[0].count").value(2))
                .andExpect(jsonPath("$.assigneeDistribution[1].userId").doesNotExist())
                .andExpect(jsonPath("$.assigneeDistribution[1].displayName").value("Unassigned"))
                .andExpect(jsonPath("$.assigneeDistribution[1].email").doesNotExist())
                .andExpect(jsonPath("$.assigneeDistribution[1].count").value(1))
                .andExpect(jsonPath("$.completionTrend[6].date").value(today))
                .andExpect(jsonPath("$.completionTrend[6].count").value(1));
    }

    @Test
    void completedAtTracksIssueAndWorkflowCategoryLifecycle() throws Exception {
        Session owner = register("analytics-lifecycle+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Completion lifecycle").get("id").asText();
        JsonNode workflowStates = listWorkflowStates(owner, projectId);
        String todoId = workflowStateId(workflowStates, "Todo");
        String inProgressId = workflowStateId(workflowStates, "In progress");
        String doneId = workflowStateId(workflowStates, "Done");
        JsonNode secondDone = readJson(postJson(
                "/api/projects/%s/workflow-states".formatted(projectId),
                "{ \"name\": \"Verified\", \"category\": \"DONE\" }",
                owner.accessToken()
        ).andExpect(status().isOk()));
        String verifiedId = secondDone.get("id").asText();

        JsonNode issueResponse = createIssue(owner, projectId, "Lifecycle issue", todoId, null);
        UUID issueId = UUID.fromString(issueResponse.get("id").asText());
        assertThat(requireIssue(issueId).getCompletedAt()).isNull();

        moveIssue(issueId, doneId, owner.accessToken()).andExpect(status().isOk());
        Instant firstCompletion = requireIssue(issueId).getCompletedAt();
        assertThat(firstCompletion).isNotNull();

        moveIssue(issueId, verifiedId, owner.accessToken()).andExpect(status().isOk());
        assertThat(requireIssue(issueId).getCompletedAt()).isEqualTo(firstCompletion);

        moveIssue(issueId, inProgressId, owner.accessToken()).andExpect(status().isOk());
        assertThat(requireIssue(issueId).getCompletedAt()).isNull();

        moveIssue(issueId, doneId, owner.accessToken()).andExpect(status().isOk());
        Instant secondCompletion = requireIssue(issueId).getCompletedAt();
        assertThat(secondCompletion).isNotNull().isAfterOrEqualTo(firstCompletion);

        patchIssue(issueId.toString(), "{ \"status\": \"ARCHIVED\" }", owner.accessToken())
                .andExpect(status().isOk());
        assertThat(requireIssue(issueId).getCompletedAt()).isEqualTo(secondCompletion);
        patchIssue(issueId.toString(), "{ \"workflowStateId\": \"%s\" }".formatted(inProgressId), owner.accessToken())
                .andExpect(status().isOk());
        assertThat(requireIssue(issueId).getCompletedAt()).isEqualTo(secondCompletion);
        patchIssue(issueId.toString(), "{ \"status\": \"IN_PROGRESS\" }", owner.accessToken())
                .andExpect(status().isOk());
        assertThat(requireIssue(issueId).getCompletedAt()).isNull();

        JsonNode categoryIssue = createIssue(owner, projectId, "Category issue", todoId, null);
        UUID categoryIssueId = UUID.fromString(categoryIssue.get("id").asText());
        patchWorkflowState(projectId, todoId, "Todo", "DONE", owner.accessToken())
                .andExpect(status().isOk());
        assertThat(requireIssue(categoryIssueId).getCompletedAt()).isNotNull();
        patchWorkflowState(projectId, todoId, "Todo", "TODO", owner.accessToken())
                .andExpect(status().isOk());
        assertThat(requireIssue(categoryIssueId).getCompletedAt()).isNull();
    }

    @Test
    void completionTrendUsesInclusiveUtcDateBoundaries() throws Exception {
        Session owner = register("analytics-boundary+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Analytics boundaries").get("id").asText();
        String doneId = workflowStateId(listWorkflowStates(owner, projectId), "Done");
        JsonNode firstDayIssue = createIssue(owner, projectId, "First day", doneId, null);
        JsonNode lastDayIssue = createIssue(owner, projectId, "Last day", doneId, null);
        JsonNode previousDayIssue = createIssue(owner, projectId, "Previous day", doneId, null);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate firstDate = today.minusDays(6);

        updateCompletedAt(
                firstDayIssue,
                firstDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        );
        updateCompletedAt(
                lastDayIssue,
                today.plusDays(1).atStartOfDay(ZoneOffset.UTC).minus(1, ChronoUnit.MICROS).toInstant()
        );
        updateCompletedAt(
                previousDayIssue,
                firstDate.atStartOfDay(ZoneOffset.UTC).minus(1, ChronoUnit.MICROS).toInstant()
        );

        JsonNode overview = readJson(getAnalytics(projectId, 7, owner.accessToken())
                .andExpect(status().isOk()));
        JsonNode trend = overview.get("completionTrend");

        assertThat(trend).hasSize(7);
        assertThat(trend.get(0).get("date").asText()).isEqualTo(firstDate.toString());
        assertThat(trend.get(0).get("count").asLong()).isEqualTo(1);
        assertThat(trend.get(6).get("date").asText()).isEqualTo(today.toString());
        assertThat(trend.get(6).get("count").asLong()).isEqualTo(1);
        assertThat(trend.valueStream().mapToLong(point -> point.get("count").asLong()).sum())
                .isEqualTo(2);
    }

    @Test
    void analyticsRejectsEveryUnsupportedRange() throws Exception {
        Session owner = register("analytics-range+" + uniqueId() + "@example.com");
        String projectId = createProject(owner, "Analytics ranges").get("id").asText();

        for (int unsupportedDays : new int[]{-1, 0, 1, 6, 8, 29, 31, 89, 91}) {
            getAnalytics(projectId, unsupportedDays, owner.accessToken())
                    .andExpect(status().isBadRequest());
        }
    }

    private Session register(String email) throws Exception {
        JsonNode response = readJson(postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Analytics owner",
                  "workspaceName": "Analytics workspace"
                }
                """.formatted(email),
                null
        ).andExpect(status().isOk()));
        return new Session(
                response.get("accessToken").asText(),
                response.get("user").get("id").asText(),
                response.get("user").get("email").asText(),
                response.get("workspace").get("id").asText()
        );
    }

    private JsonNode createProject(Session session, String name) throws Exception {
        return readJson(postJson(
                "/api/projects",
                "{ \"name\": \"%s\", \"description\": \"Analytics test\" }".formatted(name),
                session.accessToken()
        ).andExpect(status().isOk()));
    }

    private ProjectMemberSession createProjectMember(Session owner, String projectId, String displayName) {
        Workspace workspace = workspaceRepository.findById(UUID.fromString(owner.workspaceId())).orElseThrow();
        Project project = projectRepository.findById(UUID.fromString(projectId)).orElseThrow();
        User user = userRepository.save(new User(
                "member+" + uniqueId() + "@example.com",
                "unused",
                displayName
        ));
        membershipRepository.save(new WorkspaceMembership(workspace, user, WorkspaceRole.MEMBER));
        ProjectMember projectMember = projectMemberRepository.save(new ProjectMember(
                workspace,
                project,
                user,
                ProjectRole.MEMBER
        ));
        return new ProjectMemberSession(
                jwtService.generateAccessToken(
                        user,
                        membershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), user.getId()).orElseThrow()
                ),
                user.getId().toString(),
                projectMember.getId()
        );
    }

    private JsonNode listWorkflowStates(Session session, String projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{projectId}/workflow-states", projectId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String workflowStateId(JsonNode workflowStates, String name) {
        for (JsonNode workflowState : workflowStates) {
            if (name.equals(workflowState.get("name").asText())) {
                return workflowState.get("id").asText();
            }
        }
        throw new IllegalArgumentException("Workflow state not found: " + name);
    }

    private JsonNode createIssue(
            Session session,
            String projectId,
            String title,
            String workflowStateId,
            String assigneeUserId
    ) throws Exception {
        String assigneeJson = assigneeUserId == null
                ? "null"
                : "\"%s\"".formatted(assigneeUserId);
        return readJson(postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "%s",
                  "workflowStateId": "%s",
                  "assigneeUserId": %s
                }
                """.formatted(projectId, title, workflowStateId, assigneeJson),
                session.accessToken()
        ).andExpect(status().isOk()));
    }

    private ResultActions getAnalytics(String projectId, Integer days, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/analytics/overview")
                .queryParam("projectId", projectId)
                .header("Authorization", bearer(accessToken));
        if (days != null) {
            request.queryParam("days", days.toString());
        }
        return mockMvc.perform(request);
    }

    private ResultActions moveIssue(UUID issueId, String workflowStateId, String accessToken) throws Exception {
        return mockMvc.perform(patch("/api/issues/{issueId}/state", issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"workflowStateId\": \"%s\" }".formatted(workflowStateId))
                .header("Authorization", bearer(accessToken)));
    }

    private ResultActions patchIssue(String issueId, String body, String accessToken) throws Exception {
        return mockMvc.perform(patch("/api/issues/{issueId}", issueId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", bearer(accessToken)));
    }

    private ResultActions patchWorkflowState(
            String projectId,
            String workflowStateId,
            String name,
            String category,
            String accessToken
    ) throws Exception {
        return mockMvc.perform(patch(
                        "/api/projects/{projectId}/workflow-states/{workflowStateId}",
                        projectId,
                        workflowStateId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"name\": \"%s\", \"category\": \"%s\" }".formatted(name, category))
                .header("Authorization", bearer(accessToken)));
    }

    private Issue requireIssue(UUID issueId) {
        return issueRepository.findById(issueId).orElseThrow();
    }

    private void updateCompletedAt(JsonNode issue, Instant completedAt) {
        jdbcTemplate.update(
                "update issues set completed_at = ? where id = ?",
                Timestamp.from(completedAt),
                UUID.fromString(issue.get("id").asText())
        );
    }

    private record Session(
            String accessToken,
            String userId,
            String email,
            String workspaceId
    ) {
    }

    private record ProjectMemberSession(
            String accessToken,
            String userId,
            UUID projectMemberId
    ) {
    }
}
