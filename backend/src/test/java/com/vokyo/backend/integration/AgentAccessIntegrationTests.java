package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.agent.AgentAccessService;
import com.vokyo.backend.agent.AgentTokenService;
import com.vokyo.backend.project.ProjectMember;
import com.vokyo.backend.project.ProjectMemberRepository;
import com.vokyo.backend.security.AudienceJwtDecoders;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AgentAccessIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private SecretKey jwtSecretKey;
    @Autowired private WorkspaceAccessService workspaceAccessService;
    @Autowired private AgentTokenService agentTokenService;
    @Autowired private AgentAccessService agentAccessService;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkspaceMembershipRepository workspaceMembershipRepository;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    private JwtDecoder agentJwtDecoder;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        agentJwtDecoder = AudienceJwtDecoders.forAudience(jwtSecretKey, AgentTokenService.AUDIENCE);
    }

    @Test
    void activeProjectMemberCanReadTheProject() throws Exception {
        String userToken = register("reader@example.com");
        UUID projectId = createProject(userToken, "Login cleanup");

        Jwt agentJwt = agentJwtFor(userToken, projectId);

        assertThat(agentAccessService.requireAccessibleProject(agentJwt).getId()).isEqualTo(projectId);
    }

    @Test
    void workspaceClaimMustMatchTheMembership() throws Exception {
        String userToken = register("mismatch@example.com");
        UUID projectId = createProject(userToken, "Mismatch");
        Jwt agentJwt = agentJwtFor(userToken, projectId);
        Jwt tampered = Jwt.withTokenValue("unused")
            .headers(headers -> headers.putAll(agentJwt.getHeaders()))
            .claims(claims -> claims.putAll(agentJwt.getClaims()))
            .claim("workspaceId", UUID.randomUUID().toString())
            .build();

        assertThatThrownBy(() -> agentAccessService.requireAccessibleProject(tampered))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void removedProjectMemberLosesAccessBeforeTheTokenExpires() throws Exception {
        String userToken = register("removed@example.com");
        UUID projectId = createProject(userToken, "Removed");
        Jwt agentJwt = agentJwtFor(userToken, projectId);
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwtService.decode(userToken));

        ProjectMember member = projectMemberRepository.findByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
            context.workspace().getId(), projectId, context.user().getId(), MembershipStatus.ACTIVE).orElseThrow();
        member.disable();
        projectMemberRepository.save(member);

        assertThatThrownBy(() -> agentAccessService.requireAccessibleProject(agentJwt))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void disabledWorkspaceMemberLosesAccessBeforeTheTokenExpires() throws Exception {
        String userToken = register("disabled@example.com");
        UUID projectId = createProject(userToken, "Disabled");
        Jwt agentJwt = agentJwtFor(userToken, projectId);

        WorkspaceMembership membership = workspaceMembershipRepository
            .findById(UUID.fromString(agentJwt.getClaimAsString("membershipId"))).orElseThrow();
        membership.disable();
        workspaceMembershipRepository.save(membership);

        assertThatThrownBy(() -> agentAccessService.requireAccessibleProject(agentJwt))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void projectFromAnotherWorkspaceIsInvisible() throws Exception {
        String userToken = register("owner-a@example.com");
        String otherUserToken = register("owner-b@example.com");
        UUID otherWorkspaceProjectId = createProject(otherUserToken, "Someone else's project");

        Jwt agentJwt = agentJwtFor(userToken, otherWorkspaceProjectId);

        assertThatThrownBy(() -> agentAccessService.requireAccessibleProject(agentJwt))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Jwt agentJwtFor(String userToken, UUID projectId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwtService.decode(userToken));
        return agentJwtDecoder.decode(agentTokenService.issue(context, projectId, UUID.randomUUID()));
    }

    private UUID createProject(String userToken, String name) throws Exception {
        String body = mockMvc.perform(post("/api/projects")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                            { "name": "%s" }
                            """.formatted(name)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                            {
                              "email": "%s",
                              "password": "password123",
                              "displayName": "Agent Tester",
                              "workspaceName": "Agent Workspace"
                            }
                            """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
