package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.activity.ActivityEventRepository;
import com.vokyo.backend.auth.RefreshToken;
import com.vokyo.backend.auth.RefreshTokenRepository;
import com.vokyo.backend.auth.RefreshTokenService;
import com.vokyo.backend.project.ProjectLabelRepository;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceInvitation;
import com.vokyo.backend.workspace.WorkspaceInvitationRepository;
import com.vokyo.backend.workspace.WorkspaceInvitationTokenService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class WorkspaceMultiTenantIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private WorkspaceInvitationRepository invitationRepository;

    @Autowired
    private WorkspaceInvitationTokenService invitationTokenService;

    @Autowired
    private ProjectLabelRepository projectLabelRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private ProjectWorkflowStateRepository projectWorkflowStateRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void cleanDatabase() {
        activityEventRepository.deleteAllInBatch();
        invitationRepository.deleteAllInBatch();
        projectLabelRepository.deleteAllInBatch();
        projectMemberRepository.deleteAllInBatch();
        projectWorkflowStateRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        membershipRepository.deleteAllInBatch();
        workspaceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void createsListsSwitchesAndRemembersMultipleWorkspaces() throws Exception {
        Session initial = register("multi+" + uniqueId() + "@example.com", "Initial workspace");

        JsonNode firstCreated = readJson(postJson(
                "/api/workspaces",
                """
                { "name": "Product Lab" }
                """,
                initial.accessToken()
        ).andExpect(status().isOk()));
        Thread.sleep(10L);
        JsonNode secondCreated = readJson(postJson(
                "/api/workspaces",
                """
                { "name": "Product Lab" }
                """,
                initial.accessToken()
        ).andExpect(status().isOk()));

        assertThat(firstCreated.get("slug").asText()).isEqualTo("product-lab");
        assertThat(secondCreated.get("slug").asText()).startsWith("product-lab-");
        assertThat(secondCreated.get("slug").asText()).isNotEqualTo(firstCreated.get("slug").asText());
        assertThat(workspaceRepository.findAll()).hasSize(3);

        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", bearer(initial.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(secondCreated.get("id").asText()))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[2].id").value(initial.workspaceId()));

        JsonNode switched = readJson(postJson(
                "/api/workspaces/%s/switch".formatted(secondCreated.get("id").asText()),
                refreshBody(initial.refreshToken()),
                initial.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(secondCreated.get("id").asText())));

        String switchedAccessToken = switched.get("accessToken").asText();
        String switchedRefreshToken = switched.get("refreshToken").asText();
        assertThat(jwtService.getWorkspaceId(switchedAccessToken))
                .isEqualTo(UUID.fromString(secondCreated.get("id").asText()));
        assertThat(persistedRefreshToken(initial.refreshToken()).isRevoked()).isTrue();
        assertThat(persistedRefreshToken(switchedRefreshToken).isActive()).isTrue();

        postJson("/api/auth/refresh", refreshBody(initial.refreshToken()), null)
                .andExpect(status().isUnauthorized());

        JsonNode refreshed = readJson(postJson(
                "/api/auth/refresh",
                refreshBody(switchedRefreshToken),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(secondCreated.get("id").asText())));

        mockMvc.perform(get("/api/me")
                        .header("Authorization", bearer(refreshed.get("accessToken").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(secondCreated.get("id").asText()));

        postJson(
                "/api/auth/login",
                """
                {
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(initial.email()),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(secondCreated.get("id").asText()));
    }

    @Test
    void switchRejectsNonMembersDisabledMembershipsAndAnotherUsersRefreshToken() throws Exception {
        Session first = register("switch-a+" + uniqueId() + "@example.com", "First account");
        Session second = register("switch-b+" + uniqueId() + "@example.com", "Second account");

        postJson(
                "/api/workspaces/%s/switch".formatted(second.workspaceId()),
                refreshBody(first.refreshToken()),
                first.accessToken()
        ).andExpect(status().isNotFound());

        User firstUser = userRepository.findByEmail(first.email()).orElseThrow();
        Workspace secondWorkspace = workspaceRepository.findById(UUID.fromString(second.workspaceId())).orElseThrow();
        WorkspaceMembership disabledMembership = membershipRepository.saveAndFlush(new WorkspaceMembership(
                secondWorkspace,
                firstUser,
                WorkspaceRole.MEMBER
        ));
        disabledMembership.disable();
        membershipRepository.saveAndFlush(disabledMembership);

        postJson(
                "/api/workspaces/%s/switch".formatted(second.workspaceId()),
                refreshBody(first.refreshToken()),
                first.accessToken()
        ).andExpect(status().isNotFound());

        JsonNode ownedWorkspace = readJson(postJson(
                "/api/workspaces",
                "{ \"name\": \"Another owned workspace\" }",
                first.accessToken()
        ).andExpect(status().isOk()));

        postJson(
                "/api/workspaces/%s/switch".formatted(ownedWorkspace.get("id").asText()),
                refreshBody(second.refreshToken()),
                first.accessToken()
        ).andExpect(status().isUnauthorized());

        postJson("/api/auth/refresh", refreshBody(second.refreshToken()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(second.workspaceId()));
    }

    @Test
    void workspaceRolesControlInvitationsAndProjectCreation() throws Exception {
        Session owner = register("matrix-owner+" + uniqueId() + "@example.com", "Role matrix");
        Workspace workspace = workspaceRepository.findById(UUID.fromString(owner.workspaceId())).orElseThrow();
        String adminToken = addWorkspaceRole(
                register("matrix-admin+" + uniqueId() + "@example.com", "Admin home"),
                workspace,
                WorkspaceRole.ADMIN
        );
        String memberToken = addWorkspaceRole(
                register("matrix-member+" + uniqueId() + "@example.com", "Member home"),
                workspace,
                WorkspaceRole.MEMBER
        );
        String guestToken = addWorkspaceRole(
                register("matrix-guest+" + uniqueId() + "@example.com", "Guest home"),
                workspace,
                WorkspaceRole.GUEST
        );

        createInvitation(owner.accessToken(), "new-admin+" + uniqueId() + "@example.com", "ADMIN")
                .andExpect(status().isOk());
        createInvitation(owner.accessToken(), "new-owner+" + uniqueId() + "@example.com", "OWNER")
                .andExpect(status().isBadRequest());
        createInvitation(adminToken, "new-member+" + uniqueId() + "@example.com", "MEMBER")
                .andExpect(status().isOk());
        createInvitation(adminToken, "new-guest+" + uniqueId() + "@example.com", "GUEST")
                .andExpect(status().isOk());
        createInvitation(adminToken, "other-admin+" + uniqueId() + "@example.com", "ADMIN")
                .andExpect(status().isForbidden());
        createInvitation(memberToken, "member-denied+" + uniqueId() + "@example.com", "MEMBER")
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/workspaces/current/invitations")
                        .header("Authorization", bearer(guestToken)))
                .andExpect(status().isForbidden());

        postJson(
                "/api/projects",
                """
                { "name": "Member project", "description": "Allowed" }
                """,
                memberToken
        ).andExpect(status().isOk());
        postJson(
                "/api/projects",
                """
                { "name": "Guest project", "description": "Denied" }
                """,
                guestToken
        ).andExpect(status().isForbidden());
    }

    @Test
    void invitationLifecycleHashesTokensExpiresReissuesAndRevokes() throws Exception {
        Session owner = register("lifecycle-owner+" + uniqueId() + "@example.com", "Lifecycle workspace");
        String inviteeEmail = "lifecycle-invitee+" + uniqueId() + "@example.com";
        JsonNode created = readJson(createInvitation(owner.accessToken(), inviteeEmail, "MEMBER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")));
        String invitationId = created.get("id").asText();
        String originalToken = created.get("token").asText();

        WorkspaceInvitation persisted = invitationRepository.findById(UUID.fromString(invitationId)).orElseThrow();
        assertThat(persisted.getTokenHash()).isNotEqualTo(originalToken);
        assertThat(persisted.getTokenHash()).isEqualTo(invitationTokenService.hashToken(originalToken));

        mockMvc.perform(get("/api/workspace-invitations/{token}", originalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceName").value("Lifecycle workspace"))
                .andExpect(jsonPath("$.email").value(inviteeEmail))
                .andExpect(jsonPath("$.status").value("PENDING"));

        createInvitation(owner.accessToken(), inviteeEmail.toUpperCase(), "MEMBER")
                .andExpect(status().isConflict());

        Session otherOwner = register("other-owner+" + uniqueId() + "@example.com", "Other workspace");
        postJson(
                "/api/workspaces/current/invitations/%s/reissue".formatted(invitationId),
                "{}",
                otherOwner.accessToken()
        ).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/current/invitations")
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(invitationId))
                .andExpect(jsonPath("$[0].token").doesNotExist());

        jdbcTemplate.update(
                "update workspace_invitations set expires_at = now() - interval '1 minute' where id = ?::uuid",
                invitationId
        );
        mockMvc.perform(get("/api/workspace-invitations/{token}", originalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        postJson(
                "/api/auth/register-with-invitation",
                registerWithInvitationBody(originalToken, inviteeEmail),
                null
        ).andExpect(status().isGone());

        JsonNode reissued = readJson(postJson(
                "/api/workspaces/current/invitations/%s/reissue".formatted(invitationId),
                "{}",
                owner.accessToken()
        ).andExpect(status().isOk()));
        String newToken = reissued.get("token").asText();
        assertThat(newToken).isNotEqualTo(originalToken);
        mockMvc.perform(get("/api/workspace-invitations/{token}", originalToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspace-invitations/{token}", newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(delete("/api/workspaces/current/invitations/{invitationId}", invitationId)
                        .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/workspace-invitations/{token}", newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        postJson(
                "/api/auth/register-with-invitation",
                registerWithInvitationBody(newToken, inviteeEmail),
                null
        ).andExpect(status().isConflict());
        postJson(
                "/api/workspaces/current/invitations/%s/reissue".formatted(invitationId),
                "{}",
                owner.accessToken()
        ).andExpect(status().isConflict());
    }

    @Test
    void registeredUserAcceptsAndReactivatesMembershipWithoutProjectAccess() throws Exception {
        Session owner = register("accept-owner+" + uniqueId() + "@example.com", "Accept workspace");
        JsonNode project = readJson(postJson(
                "/api/projects",
                "{ \"name\": \"Private project\", \"description\": \"Project membership required\" }",
                owner.accessToken()
        ).andExpect(status().isOk()));
        Session invitee = register("accept-user+" + uniqueId() + "@example.com", "Invitee home");
        Session wrongAccount = register("wrong-account+" + uniqueId() + "@example.com", "Wrong account");

        Workspace targetWorkspace = workspaceRepository.findById(UUID.fromString(owner.workspaceId())).orElseThrow();
        User inviteeUser = userRepository.findByEmail(invitee.email()).orElseThrow();
        WorkspaceMembership disabledMembership = membershipRepository.saveAndFlush(new WorkspaceMembership(
                targetWorkspace,
                inviteeUser,
                WorkspaceRole.GUEST
        ));
        UUID originalMembershipId = disabledMembership.getId();
        disabledMembership.disable();
        membershipRepository.saveAndFlush(disabledMembership);

        JsonNode invitation = readJson(createInvitation(
                owner.accessToken(),
                invitee.email(),
                "MEMBER"
        ).andExpect(status().isOk()));
        String token = invitation.get("token").asText();

        postJson(
                "/api/workspace-invitations/%s/accept".formatted(token),
                refreshBody(wrongAccount.refreshToken()),
                wrongAccount.accessToken()
        ).andExpect(status().isForbidden());

        JsonNode accepted = readJson(postJson(
                "/api/workspace-invitations/%s/accept".formatted(token),
                refreshBody(invitee.refreshToken()),
                invitee.accessToken()
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(owner.workspaceId()))
                .andExpect(jsonPath("$.workspace.role").value("MEMBER")));

        WorkspaceMembership reactivated = membershipRepository.findByWorkspace_IdAndUser_Id(
                UUID.fromString(owner.workspaceId()),
                UUID.fromString(invitee.userId())
        ).orElseThrow();
        assertThat(reactivated.getId()).isEqualTo(originalMembershipId);
        assertThat(reactivated.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(reactivated.getRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(persistedRefreshToken(invitee.refreshToken()).isRevoked()).isTrue();

        MvcResult projectsResult = mockMvc.perform(get("/api/projects")
                        .header("Authorization", bearer(accepted.get("accessToken").asText())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(projectsResult.getResponse().getContentAsString())).isEmpty();
        mockMvc.perform(get("/api/projects/{projectId}", project.get("id").asText())
                        .header("Authorization", bearer(accepted.get("accessToken").asText())))
                .andExpect(status().isNotFound());

        postJson(
                "/api/workspace-invitations/%s/accept".formatted(token),
                refreshBody(accepted.get("refreshToken").asText()),
                accepted.get("accessToken").asText()
        ).andExpect(status().isConflict());
        postJson(
                "/api/auth/refresh",
                refreshBody(accepted.get("refreshToken").asText()),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(owner.workspaceId()));
        createInvitation(owner.accessToken(), invitee.email(), "MEMBER")
                .andExpect(status().isConflict());
    }

    @Test
    void unregisteredUserRegistersDirectlyIntoInvitedWorkspace() throws Exception {
        Session owner = register("registration-owner+" + uniqueId() + "@example.com", "Registration workspace");
        String invitedEmail = "new-user+" + uniqueId() + "@example.com";
        JsonNode invitation = readJson(createInvitation(
                owner.accessToken(),
                invitedEmail,
                "GUEST"
        ).andExpect(status().isOk()));
        String token = invitation.get("token").asText();

        postJson(
                "/api/auth/register-with-invitation",
                registerWithInvitationBody(token, "different+" + uniqueId() + "@example.com"),
                null
        ).andExpect(status().isForbidden());

        JsonNode registered = readJson(postJson(
                "/api/auth/register-with-invitation",
                registerWithInvitationBody(token, invitedEmail.toUpperCase()),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(invitedEmail))
                .andExpect(jsonPath("$.workspace.id").value(owner.workspaceId()))
                .andExpect(jsonPath("$.workspace.role").value("GUEST")));

        User invitedUser = userRepository.findByEmail(invitedEmail).orElseThrow();
        List<WorkspaceMembership> memberships =
                membershipRepository.findByUser_IdAndStatusOrderByLastAccessedAtDescJoinedAtAsc(
                        invitedUser.getId(),
                        MembershipStatus.ACTIVE
                );
        assertThat(workspaceRepository.findAll()).hasSize(1);
        assertThat(workspaceRepository.findFirstByOwner_IdOrderByCreatedAtAsc(invitedUser.getId())).isEmpty();
        assertThat(memberships).hasSize(1);
        assertThat(memberships.getFirst().getWorkspace().getId()).isEqualTo(UUID.fromString(owner.workspaceId()));
        assertThat(memberships.getFirst().getRole()).isEqualTo(WorkspaceRole.GUEST);

        postJson(
                "/api/auth/refresh",
                refreshBody(registered.get("refreshToken").asText()),
                null
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(owner.workspaceId()));
        postJson(
                "/api/auth/register-with-invitation",
                registerWithInvitationBody(token, invitedEmail),
                null
        ).andExpect(status().isConflict());
    }

    @Test
    void concurrentInvitationAcceptAndRefreshEachProduceOneSuccessor() throws Exception {
        Session owner = register("concurrent-owner+" + uniqueId() + "@example.com", "Concurrent workspace");
        Session invitee = register("concurrent-user+" + uniqueId() + "@example.com", "Concurrent home");
        JsonNode invitation = readJson(createInvitation(
                owner.accessToken(),
                invitee.email(),
                "MEMBER"
        ).andExpect(status().isOk()));
        String token = invitation.get("token").asText();

        List<Integer> acceptStatuses = runConcurrently(() -> postJson(
                "/api/workspace-invitations/%s/accept".formatted(token),
                refreshBody(invitee.refreshToken()),
                invitee.accessToken()
        ).andReturn().getResponse().getStatus());
        assertThat(acceptStatuses).containsExactly(200, 409);

        Session refreshSession = register(
                "concurrent-refresh+" + uniqueId() + "@example.com",
                "Concurrent refresh"
        );
        List<Integer> refreshStatuses = runConcurrently(() -> postJson(
                "/api/auth/refresh",
                refreshBody(refreshSession.refreshToken()),
                null
        ).andReturn().getResponse().getStatus());
        assertThat(refreshStatuses).containsExactly(200, 401);
    }

    private ResultActions createInvitation(String accessToken, String email, String role) throws Exception {
        return postJson(
                "/api/workspaces/current/invitations",
                """
                {
                  "email": "%s",
                  "role": "%s"
                }
                """.formatted(email, role),
                accessToken
        );
    }

    private String addWorkspaceRole(Session session, Workspace workspace, WorkspaceRole role) {
        User user = userRepository.findByEmail(session.email()).orElseThrow();
        WorkspaceMembership membership = membershipRepository.saveAndFlush(new WorkspaceMembership(
                workspace,
                user,
                role
        ));
        return jwtService.generateAccessToken(user, membership);
    }

    private Session register(String email, String workspaceName) throws Exception {
        JsonNode response = readJson(postJson(
                "/api/auth/register",
                """
                {
                  "email": "%s",
                  "password": "password123",
                  "displayName": "Workspace test user",
                  "workspaceName": "%s"
                }
                """.formatted(email, workspaceName),
                null
        ).andExpect(status().isOk()));

        return new Session(
                response.get("accessToken").asText(),
                response.get("refreshToken").asText(),
                response.get("user").get("id").asText(),
                response.get("user").get("email").asText(),
                response.get("workspace").get("id").asText()
        );
    }

    private ResultActions postJson(String path, String json, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (accessToken != null) {
            request.header("Authorization", bearer(accessToken));
        }
        return mockMvc.perform(request);
    }

    private JsonNode readJson(ResultActions resultActions) throws Exception {
        MvcResult result = resultActions.andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private RefreshToken persistedRefreshToken(String plainToken) {
        return refreshTokenRepository.findByTokenHash(refreshTokenService.hashToken(plainToken)).orElseThrow();
    }

    private String refreshBody(String refreshToken) {
        return """
                { "refreshToken": "%s" }
                """.formatted(refreshToken);
    }

    private String registerWithInvitationBody(String token, String email) {
        return """
                {
                  "token": "%s",
                  "email": "%s",
                  "displayName": "Invited user",
                  "password": "password123"
                }
                """.formatted(token, email);
    }

    private List<Integer> runConcurrently(ConcurrentRequest request) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return request.execute();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(10, TimeUnit.SECONDS));
            }
            statuses.sort(Integer::compareTo);
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private String uniqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @FunctionalInterface
    private interface ConcurrentRequest {
        int execute() throws Exception;
    }

    private record Session(
            String accessToken,
            String refreshToken,
            String userId,
            String email,
            String workspaceId
    ) {
    }
}
