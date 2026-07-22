package com.vokyo.backend;

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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "RUN_AI_SMOKE_TEST", matches = "true")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.rate-limit.enabled=false",
        "spring.ai.model.chat=openai",
        "spring.ai.retry.max-attempts=1"
})
class OpenAiProviderSmokeIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private JwtService jwtService;

    @Test
    void generatesStructuredProjectSummaryThroughTheRealHttpPath() throws Exception {
        User user = userRepository.save(new User(
                "ai-smoke@example.invalid",
                "not-a-real-password-hash",
                "AI Smoke User"
        ));
        Workspace workspace = workspaceRepository.save(new Workspace(
                user,
                "AI Smoke Workspace",
                "ai-smoke-" + uniqueId()
        ));
        WorkspaceMembership membership = membershipRepository.save(
                new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER)
        );
        Project project = projectRepository.save(new Project(
                workspace,
                user,
                "Non-sensitive smoke project",
                "A fixture with no customer data and no active issues."
        ));
        projectMemberRepository.save(new ProjectMember(
                workspace,
                project,
                user,
                ProjectRole.OWNER
        ));

        postJson(
                "/api/ai/projects/%s/summary".formatted(project.getId()),
                "{\"rangeDays\":7}",
                jwtService.generateAccessToken(user, membership)
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PROJECT_SUMMARY"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.content.executiveSummary.length()", greaterThan(0)))
                .andExpect(jsonPath("$.content.sourceStats.activeIssuesUsed").value(0))
                .andExpect(jsonPath("$.metadata.provider").isNotEmpty())
                .andExpect(jsonPath("$.metadata.model").isNotEmpty());
    }
}
