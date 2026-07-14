package com.vokyo.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class TenantDatabaseIntegrityIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    private TenantGraph tenantA;
    private TenantGraph tenantB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        tenantA = createTenantGraph("a");
        tenantB = createTenantGraph("b");
    }

    @Test
    void acceptsRowsWhoseTenantScopedReferencesAreConsistent() {
        UUID memberId = UUID.randomUUID();
        UUID workflowStateId = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID refreshTokenId = UUID.randomUUID();

        insertProjectMember(memberId, tenantA.workspaceId(), tenantA.projectId(), tenantA.userId());
        insertWorkflowState(
                workflowStateId,
                tenantA.workspaceId(),
                tenantA.projectId(),
                "In progress",
                20_000
        );
        insertLabel(labelId, tenantA.workspaceId(), tenantA.projectId(), "Backend", "#2563EB");
        insertIssue(
                issueId,
                tenantA.workspaceId(),
                tenantA.projectId(),
                tenantA.userId(),
                workflowStateId,
                "Valid issue"
        );
        insertComment(
                commentId,
                tenantA.workspaceId(),
                tenantA.projectId(),
                issueId,
                tenantA.userId()
        );
        insertActivity(
                activityId,
                tenantA.workspaceId(),
                tenantA.projectId(),
                issueId,
                tenantA.userId()
        );
        insertIssueLabel(issueId, labelId);
        insertRefreshToken(
                refreshTokenId,
                tenantA.userId(),
                tenantA.membershipId()
        );

        assertThat(rowExists("project_members", "id", memberId)).isTrue();
        assertThat(rowExists("project_workflow_states", "id", workflowStateId)).isTrue();
        assertThat(rowExists("project_labels", "id", labelId)).isTrue();
        assertThat(rowExists("issues", "id", issueId)).isTrue();
        assertThat(rowExists("issue_comments", "id", commentId)).isTrue();
        assertThat(rowExists("activity_events", "id", activityId)).isTrue();
        assertThat(rowExists("refresh_tokens", "id", refreshTokenId)).isTrue();
        assertThat(jdbcTemplate.queryForMap(
                """
                select workspace_id, project_id
                from issue_labels
                where issue_id = ? and label_id = ?
                """,
                issueId,
                labelId
        )).containsEntry("workspace_id", tenantA.workspaceId())
                .containsEntry("project_id", tenantA.projectId());
    }

    @Test
    void rejectsProjectMemberWhoseProjectBelongsToAnotherWorkspace() {
        assertThatThrownBy(() -> insertProjectMember(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantB.projectId(),
                tenantA.userId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsProjectMemberWhoseUserIsNotAMemberOfTheWorkspace() {
        assertThatThrownBy(() -> insertProjectMember(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantA.projectId(),
                tenantB.userId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsWorkflowStateWhoseProjectBelongsToAnotherWorkspace() {
        assertThatThrownBy(() -> insertWorkflowState(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantB.projectId(),
                "Invalid state",
                40_000
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsLabelWhoseProjectBelongsToAnotherWorkspace() {
        assertThatThrownBy(() -> insertLabel(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantB.projectId(),
                "Invalid label",
                "#DC2626"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsIssueWhoseProjectOrWorkflowStateBelongsToAnotherTenantGraph() {
        assertThatThrownBy(() -> insertIssue(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantB.projectId(),
                tenantA.userId(),
                tenantB.workflowStateId(),
                "Cross-workspace project"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertIssue(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantA.projectId(),
                tenantA.userId(),
                tenantB.workflowStateId(),
                "Cross-workspace workflow state"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCommentWhoseIssueBelongsToAnotherTenantGraph() {
        assertThatThrownBy(() -> insertComment(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantA.projectId(),
                tenantB.issueId(),
                tenantA.userId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsActivityWhoseIssueBelongsToAnotherTenantGraph() {
        assertThatThrownBy(() -> insertActivity(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                tenantA.projectId(),
                tenantB.issueId(),
                tenantA.userId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsWorkspaceOnlyAndProjectOnlyActivitiesButRejectsIssueWithoutProject() {
        UUID workspaceActivityId = UUID.randomUUID();
        UUID projectActivityId = UUID.randomUUID();

        insertActivity(
                workspaceActivityId,
                tenantA.workspaceId(),
                null,
                null,
                tenantA.userId()
        );
        insertActivity(
                projectActivityId,
                tenantA.workspaceId(),
                tenantA.projectId(),
                null,
                tenantA.userId()
        );

        assertThat(rowExists("activity_events", "id", workspaceActivityId)).isTrue();
        assertThat(rowExists("activity_events", "id", projectActivityId)).isTrue();
        assertThatThrownBy(() -> insertActivity(
                UUID.randomUUID(),
                tenantA.workspaceId(),
                null,
                tenantA.issueId(),
                tenantA.userId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsIssueLabelWhoseIssueOrLabelBelongsToAnotherTenantGraph() {
        assertThatThrownBy(() -> insertIssueLabel(
                tenantB.issueId(),
                tenantA.labelId()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertIssueLabel(
                tenantA.issueId(),
                tenantB.labelId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsRefreshTokenWhoseMembershipBelongsToAnotherUser() {
        assertThatThrownBy(() -> insertRefreshToken(
                UUID.randomUUID(),
                tenantA.userId(),
                tenantB.membershipId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private TenantGraph createTenantGraph(String suffix) {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID workflowStateId = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();

        jdbcTemplate.update(
                "insert into users (id, email, password_hash, display_name) values (?, ?, ?, ?)",
                userId,
                "tenant-" + suffix + "-" + UUID.randomUUID() + "@example.com",
                "test-password-hash",
                "Tenant " + suffix.toUpperCase()
        );
        jdbcTemplate.update(
                "insert into workspaces (id, owner_user_id, name, slug) values (?, ?, ?, ?)",
                workspaceId,
                userId,
                "Workspace " + suffix.toUpperCase(),
                "workspace-" + suffix + "-" + UUID.randomUUID()
        );
        jdbcTemplate.update(
                """
                insert into workspace_memberships
                    (id, workspace_id, user_id, role, status, last_accessed_at)
                values (?, ?, ?, 'OWNER', 'ACTIVE', now())
                """,
                membershipId,
                workspaceId,
                userId
        );
        jdbcTemplate.update(
                "insert into projects (id, workspace_id, created_by_user_id, name) values (?, ?, ?, ?)",
                projectId,
                workspaceId,
                userId,
                "Project " + suffix.toUpperCase()
        );
        insertWorkflowState(workflowStateId, workspaceId, projectId, "Todo", 10_000);
        insertLabel(labelId, workspaceId, projectId, "Seed " + suffix.toUpperCase(), "#64748B");
        insertIssue(issueId, workspaceId, projectId, userId, workflowStateId, "Issue " + suffix.toUpperCase());

        return new TenantGraph(
                userId,
                workspaceId,
                membershipId,
                projectId,
                workflowStateId,
                labelId,
                issueId
        );
    }

    private void insertProjectMember(UUID id, UUID workspaceId, UUID projectId, UUID userId) {
        jdbcTemplate.update(
                """
                insert into project_members (id, workspace_id, project_id, user_id, role, status)
                values (?, ?, ?, ?, 'MEMBER', 'ACTIVE')
                """,
                id,
                workspaceId,
                projectId,
                userId
        );
    }

    private void insertWorkflowState(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            String name,
            int position
    ) {
        jdbcTemplate.update(
                """
                insert into project_workflow_states
                    (id, workspace_id, project_id, name, category, position)
                values (?, ?, ?, ?, 'TODO', ?)
                """,
                id,
                workspaceId,
                projectId,
                name,
                position
        );
    }

    private void insertLabel(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            String name,
            String color
    ) {
        jdbcTemplate.update(
                """
                insert into project_labels (id, workspace_id, project_id, name, color)
                values (?, ?, ?, ?, ?)
                """,
                id,
                workspaceId,
                projectId,
                name,
                color
        );
    }

    private void insertIssue(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            UUID userId,
            UUID workflowStateId,
            String title
    ) {
        jdbcTemplate.update(
                """
                insert into issues
                    (id, workspace_id, project_id, created_by_user_id, title, status,
                     workflow_state_id, board_position)
                values (?, ?, ?, ?, ?, 'TODO', ?, 10000)
                """,
                id,
                workspaceId,
                projectId,
                userId,
                title,
                workflowStateId
        );
    }

    private void insertComment(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            UUID issueId,
            UUID userId
    ) {
        jdbcTemplate.update(
                """
                insert into issue_comments
                    (id, workspace_id, project_id, issue_id, author_user_id, body)
                values (?, ?, ?, ?, ?, 'Test comment')
                """,
                id,
                workspaceId,
                projectId,
                issueId,
                userId
        );
    }

    private void insertActivity(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            UUID issueId,
            UUID userId
    ) {
        jdbcTemplate.update(
                """
                insert into activity_events
                    (id, workspace_id, project_id, issue_id, actor_user_id, event_type)
                values (?, ?, ?, ?, ?, 'ISSUE_CREATED')
                """,
                id,
                workspaceId,
                projectId,
                issueId,
                userId
        );
    }

    private void insertIssueLabel(UUID issueId, UUID labelId) {
        jdbcTemplate.update(
                """
                insert into issue_labels (issue_id, label_id)
                values (?, ?)
                """,
                issueId,
                labelId
        );
    }

    private void insertRefreshToken(UUID id, UUID userId, UUID membershipId) {
        jdbcTemplate.update(
                """
                insert into refresh_tokens
                    (id, user_id, workspace_membership_id, token_hash, expires_at)
                values (?, ?, ?, ?, now() + interval '1 day')
                """,
                id,
                userId,
                membershipId,
                "token-hash-" + id
        );
    }

    private boolean rowExists(String table, String idColumn, UUID id) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + idColumn + " = ?",
                Long.class,
                id
        );
        return count != null && count == 1L;
    }

    private record TenantGraph(
            UUID userId,
            UUID workspaceId,
            UUID membershipId,
            UUID projectId,
            UUID workflowStateId,
            UUID labelId,
            UUID issueId
    ) {
    }
}
