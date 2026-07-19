package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import com.vokyo.backend.ai.suggestion.AiSuggestionRepository;
import com.vokyo.backend.ai.suggestion.AiSuggestionStatus;
import com.vokyo.backend.ai.suggestion.AiSuggestionType;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.issue.IssueRepository;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectRepository;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.ProjectWorkflowStateRepository;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class AiSuggestionPersistenceIntegrationTests {

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    @Autowired
    private AiSuggestionRepository suggestionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectWorkflowStateRepository workflowStateRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    @Test
    void persistsAndReloadsValidatedJsonbDraft() {
        TenantGraph graph = createTenantGraph("persist");
        JsonNode content = objectMapper.createObjectNode()
                .put("overview", "Split into independently verifiable tasks")
                .set("items", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("clientItemId", "item-1")
                                .put("title", "Define the response contract")));

        AiSuggestion saved = suggestionRepository.saveAndFlush(new AiSuggestion(
                graph.workspace(),
                graph.project(),
                graph.issue(),
                graph.user(),
                AiSuggestionType.ISSUE_BREAKDOWN,
                content,
                "issue-breakdown-v1",
                "fake",
                "fake-model",
                "a".repeat(64),
                12,
                24,
                Instant.now().plusSeconds(3600)
        ));

        entityManager.clear();

        AiSuggestion reloaded = suggestionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AiSuggestionStatus.DRAFT);
        assertThat(reloaded.getWorkspace().getId()).isEqualTo(graph.workspace().getId());
        assertThat(reloaded.getProject().getId()).isEqualTo(graph.project().getId());
        assertThat(reloaded.getSourceIssue().getId()).isEqualTo(graph.issue().getId());
        assertThat(reloaded.getContent().get("overview").asText())
                .isEqualTo("Split into independently verifiable tasks");
        assertThat(reloaded.getContent().get("items").get(0).get("title").asText())
                .isEqualTo("Define the response contract");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getExpiresAt()).isAfter(reloaded.getCreatedAt());
    }

    @Test
    void databaseRejectsSourceIssueFromAnotherTenantGraph() {
        TenantGraph tenantA = createTenantGraph("tenant-a");
        TenantGraph tenantB = createTenantGraph("tenant-b");

        AiSuggestion crossTenantSuggestion = new AiSuggestion(
                tenantA.workspace(),
                tenantA.project(),
                tenantB.issue(),
                tenantA.user(),
                AiSuggestionType.ISSUE_BREAKDOWN,
                objectMapper.createObjectNode().put("overview", "Invalid"),
                "issue-breakdown-v1",
                "fake",
                "fake-model",
                "b".repeat(64),
                null,
                null,
                Instant.now().plusSeconds(3600)
        );

        assertThatThrownBy(() -> suggestionRepository.saveAndFlush(crossTenantSuggestion))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsCreatorWithoutWorkspaceMembership() {
        TenantGraph graph = createTenantGraph("member");
        User outsider = userRepository.save(new User(
                "outsider-" + System.nanoTime() + "@example.com",
                "password-hash",
                "Outsider"
        ));

        AiSuggestion invalidCreator = new AiSuggestion(
                graph.workspace(),
                graph.project(),
                graph.issue(),
                outsider,
                AiSuggestionType.ISSUE_SUMMARY,
                objectMapper.createObjectNode().put("summary", "Invalid"),
                "issue-summary-v1",
                "fake",
                "fake-model",
                "c".repeat(64),
                null,
                null,
                Instant.now().plusSeconds(3600)
        );

        assertThatThrownBy(() -> suggestionRepository.saveAndFlush(invalidCreator))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TenantGraph createTenantGraph(String suffix) {
        long unique = System.nanoTime();
        User user = userRepository.save(new User(
                suffix + "-" + unique + "@example.com",
                "password-hash",
                "Test User"
        ));
        Workspace workspace = workspaceRepository.save(new Workspace(
                user,
                "Workspace " + suffix,
                suffix + "-" + unique
        ));
        membershipRepository.save(new WorkspaceMembership(
                workspace,
                user,
                WorkspaceRole.OWNER
        ));
        Project project = projectRepository.save(new Project(
                workspace,
                user,
                "Project " + suffix,
                "Project description"
        ));
        ProjectWorkflowState workflowState = workflowStateRepository.save(
                new ProjectWorkflowState(
                        workspace,
                        project,
                        "Todo",
                        WorkflowStateCategory.TODO,
                        10_000
                )
        );
        Issue issue = issueRepository.save(new Issue(
                workspace,
                project,
                user,
                "Source issue",
                "Source description",
                null,
                workflowState,
                IssuePriority.HIGH,
                null,
                10_000L
        ));

        return new TenantGraph(user, workspace, project, issue);
    }

    private record TenantGraph(
            User user,
            Workspace workspace,
            Project project,
            Issue issue
    ) {
    }
}
