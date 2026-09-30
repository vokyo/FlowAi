package com.vokyo.backend.integration;

import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectRole;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AgentInternalApiIntegrationTests {

    private static final String ISSUES = "/api/internal/agent/project/issues";
    private static final String MEMBERS = "/api/internal/agent/project/members";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private AgentTokenService agentTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectWorkflowStateRepository workflowStateRepository;
    @Autowired private IssueRepository issueRepository;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    private long nextBoardPosition;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
        nextBoardPosition = 10_000;
    }

    @Test
    void searchReturnsActiveIssuesOfTheTokenProjectInTheAgentShape() throws Exception {
        Tenant tenant = tenant("search");
        ProjectFixture fixture = project(tenant, "Login cleanup");
        issue(tenant, fixture, "Fix login timeout", fixture.todo(), tenant.owner(), IssuePriority.HIGH);
        issue(tenant, fixture, "Refresh login tokens", fixture.done(), null, null);
        issue(tenant, fixture, "Update README", fixture.todo(), null, IssuePriority.LOW);
        Issue archived = issue(tenant, fixture, "Old login page", fixture.todo(), null, IssuePriority.LOW);
        archived.archive();
        issueRepository.saveAndFlush(archived);

        asAgent(agentToken(tenant, fixture.project()), get(ISSUES).param("q", "LOGIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.items[*].title",
                containsInAnyOrder("Fix login timeout", "Refresh login tokens")))
            .andExpect(jsonPath("$.items[?(@.title == 'Fix login timeout')].status").value("TODO"))
            .andExpect(jsonPath("$.items[?(@.title == 'Fix login timeout')].priority").value("HIGH"))
            .andExpect(jsonPath("$.items[?(@.title == 'Fix login timeout')].assigneeUserId")
                .value(tenant.owner().getId().toString()))
            .andExpect(jsonPath("$.items[?(@.title == 'Fix login timeout')].assigneeDisplayName")
                .value(tenant.owner().getDisplayName()))
            .andExpect(jsonPath("$.items[?(@.title == 'Refresh login tokens')].status").value("DONE"))
            .andExpect(jsonPath("$.items[*].description").doesNotExist())
            .andExpect(jsonPath("$.items[*].projectId").doesNotExist());
    }

    @Test
    void aProjectIdInTheQueryStringCannotSwitchTheTokensProject() throws Exception {
        Tenant tenant = tenant("forged");
        ProjectFixture bound = project(tenant, "Bound");
        ProjectFixture other = project(tenant, "Other");
        issue(tenant, bound, "Bound login issue", bound.todo(), null, IssuePriority.HIGH);
        issue(tenant, other, "Other login issue", other.todo(), null, IssuePriority.HIGH);
        member(tenant, other.project(), "Only In Other");
        String token = agentToken(tenant, bound.project());
        String otherProjectId = other.project().getId().toString();

        asAgent(token, get(ISSUES).param("q", "login").param("projectId", otherProjectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title", contains("Bound login issue")));
        asAgent(token, get(MEMBERS).param("projectId", otherProjectId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].displayName", not(hasItem("Only In Other"))));
    }

    @Test
    void aTokenForAnotherWorkspacesProjectReadsNothing() throws Exception {
        Tenant mine = tenant("mine");
        Tenant theirs = tenant("theirs");
        ProjectFixture theirProject = project(theirs, "Theirs");
        issue(theirs, theirProject, "Their login issue", theirProject.todo(), null, IssuePriority.HIGH);
        String token = agentToken(mine, theirProject.project());

        asAgent(token, get(ISSUES)).andExpect(status().isNotFound());
        asAgent(token, get(MEMBERS)).andExpect(status().isNotFound());
    }

    @Test
    void removingTheUserFromTheProjectCutsOffATokenThatHasNotExpired() throws Exception {
        Tenant tenant = tenant("removed");
        ProjectFixture fixture = project(tenant, "Removed");
        String token = agentToken(tenant, fixture.project());
        asAgent(token, get(ISSUES)).andExpect(status().isOk());

        ProjectMember membership = projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_Id(
            tenant.workspace().getId(), fixture.project().getId(), tenant.owner().getId()).orElseThrow();
        membership.disable();
        projectMemberRepository.saveAndFlush(membership);

        asAgent(token, get(ISSUES)).andExpect(status().isNotFound());
        asAgent(token, get(MEMBERS)).andExpect(status().isNotFound());
    }

    @Test
    void anExpiredAgentTokenIsRejectedBeforeTheController() throws Exception {
        Tenant tenant = tenant("expired");
        ProjectFixture fixture = project(tenant, "Expired");
        Instant now = Instant.now();

        // Same claims, still valid: proves the 401 below comes from the expiry alone.
        asAgent(signedAgentToken(tenant, fixture.project(), now.minus(Duration.ofMinutes(1))), get(ISSUES))
            .andExpect(status().isOk());
        // Expired five minutes ago, well past the decoder's default 60-second clock skew.
        asAgent(signedAgentToken(tenant, fixture.project(), now.minus(Duration.ofMinutes(20))), get(ISSUES))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void issueSearchIsCappedAndSaysWhenItCutResultsOff() throws Exception {
        Tenant tenant = tenant("large");
        ProjectFixture fixture = project(tenant, "Large");
        for (int number = 1; number <= 23; number++) {
            issue(tenant, fixture, "Login task " + number, fixture.todo(), null, IssuePriority.MEDIUM);
        }
        for (String suffix : List.of("A", "B", "C")) {
            issue(tenant, fixture, "Session cleanup " + suffix, fixture.todo(), null, IssuePriority.LOW);
        }
        String token = agentToken(tenant, fixture.project());

        asAgent(token, get(ISSUES))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.truncated").value(true));
        asAgent(token, get(ISSUES).param("limit", "5"))
            .andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.truncated").value(true));
        asAgent(token, get(ISSUES).param("q", "Session cleanup").param("limit", "3"))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void searchArgumentsOutsideTheirLimitsAreRejected() throws Exception {
        Tenant tenant = tenant("arguments");
        ProjectFixture fixture = project(tenant, "Arguments");
        String token = agentToken(tenant, fixture.project());

        asAgent(token, get(ISSUES).param("limit", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("limit must be between 1 and 20"));
        asAgent(token, get(ISSUES).param("limit", "21"))
            .andExpect(status().isBadRequest());
        asAgent(token, get(ISSUES).param("q", "x".repeat(101)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("q must be at most 100 characters"));
        asAgent(token, get(ISSUES).param("q", "   "))
            .andExpect(status().isOk());
    }

    @Test
    void membersListsOnlyActiveMembersAndLeavesOutEmails() throws Exception {
        Tenant tenant = tenant("members");
        ProjectFixture fixture = project(tenant, "Members");
        User active = member(tenant, fixture.project(), "Active Member");
        User removed = member(tenant, fixture.project(), "Removed Member");
        ProjectMember removedMembership = projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_Id(
            tenant.workspace().getId(), fixture.project().getId(), removed.getId()).orElseThrow();
        removedMembership.disable();
        projectMemberRepository.saveAndFlush(removedMembership);

        asAgent(agentToken(tenant, fixture.project()), get(MEMBERS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.items[*].displayName",
                containsInAnyOrder(tenant.owner().getDisplayName(), "Active Member")))
            .andExpect(jsonPath("$.items[?(@.displayName == 'Active Member')].userId")
                .value(active.getId().toString()))
            .andExpect(jsonPath("$.items[?(@.displayName == 'Active Member')].role").value("MEMBER"))
            .andExpect(jsonPath("$.items[*].email").doesNotExist());
    }

    @Test
    void memberListIsCappedAndSaysWhenItCutResultsOff() throws Exception {
        Tenant tenant = tenant("crowded");
        ProjectFixture fixture = project(tenant, "Crowded");
        for (int number = 1; number <= 50; number++) {
            member(tenant, fixture.project(), "Member " + number);
        }

        asAgent(agentToken(tenant, fixture.project()), get(MEMBERS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(50))
            .andExpect(jsonPath("$.truncated").value(true));
    }

    private ResultActions asAgent(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private String agentToken(Tenant tenant, Project project) {
        CurrentWorkspaceContext context = new CurrentWorkspaceContext(
            tenant.owner(), tenant.workspace(), tenant.membership());
        return agentTokenService.issue(context, project.getId(), UUID.randomUUID());
    }

    private String signedAgentToken(Tenant tenant, Project project, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("flowai")
            .audience(List.of(AgentTokenService.AUDIENCE))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
            .subject(tenant.owner().getId().toString())
            .claim("workspaceId", tenant.workspace().getId().toString())
            .claim("membershipId", tenant.membership().getId().toString())
            .claim("projectId", project.getId().toString())
            .claim("runId", UUID.randomUUID().toString())
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private Tenant tenant(String name) {
        long unique = System.nanoTime();
        User owner = userRepository.save(new User(
            name + "-" + unique + "@example.com", "password-hash", "Owner " + name));
        Workspace workspace = workspaceRepository.save(new Workspace(
            owner, "Workspace " + name, name + "-" + unique));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
            workspace, owner, WorkspaceRole.OWNER));
        return new Tenant(owner, workspace, membership);
    }

    private ProjectFixture project(Tenant tenant, String name) {
        Project project = projectRepository.save(new Project(
            tenant.workspace(), tenant.owner(), name, "Project " + name));
        projectMemberRepository.save(new ProjectMember(
            tenant.workspace(), project, tenant.owner(), ProjectRole.OWNER));
        ProjectWorkflowState todo = workflowStateRepository.save(new ProjectWorkflowState(
            tenant.workspace(), project, "Todo", WorkflowStateCategory.TODO, 10_000));
        ProjectWorkflowState done = workflowStateRepository.save(new ProjectWorkflowState(
            tenant.workspace(), project, "Done", WorkflowStateCategory.DONE, 20_000));
        return new ProjectFixture(project, todo, done);
    }

    private User member(Tenant tenant, Project project, String displayName) {
        User user = userRepository.save(new User(
            "member-" + System.nanoTime() + "@example.com", "password-hash", displayName));
        membershipRepository.save(new WorkspaceMembership(tenant.workspace(), user, WorkspaceRole.MEMBER));
        projectMemberRepository.save(new ProjectMember(tenant.workspace(), project, user, ProjectRole.MEMBER));
        return user;
    }

    private Issue issue(
        Tenant tenant,
        ProjectFixture fixture,
        String title,
        ProjectWorkflowState state,
        User assignee,
        IssuePriority priority
    ) {
        nextBoardPosition += 10_000;
        return issueRepository.save(new Issue(
            tenant.workspace(),
            fixture.project(),
            tenant.owner(),
            title,
            "Description of " + title,
            assignee,
            state,
            priority,
            null,
            nextBoardPosition
        ));
    }

    private record Tenant(User owner, Workspace workspace, WorkspaceMembership membership) {
    }

    private record ProjectFixture(Project project, ProjectWorkflowState todo, ProjectWorkflowState done) {
    }
}
