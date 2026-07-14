package com.vokyo.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayTenantPrecheckIntegrationTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void v13RejectsExistingCrossWorkspaceRowsAndNamesTheAffectedTable() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("12"))
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID workspaceA = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();

        insertUser(jdbc, userA, "precheck-a@example.com");
        insertUser(jdbc, userB, "precheck-b@example.com");
        insertWorkspace(jdbc, workspaceA, userA, "precheck-a");
        insertWorkspace(jdbc, workspaceB, userB, "precheck-b");
        jdbc.update(
                """
                insert into workspace_memberships
                    (id, workspace_id, user_id, role, status, last_accessed_at)
                values (?, ?, ?, 'OWNER', 'ACTIVE', now())
                """,
                UUID.randomUUID(), workspaceA, userA
        );
        jdbc.update(
                "insert into projects (id, workspace_id, created_by_user_id, name) values (?, ?, ?, ?)",
                projectB, workspaceB, userB, "Workspace B project"
        );
        jdbc.update(
                """
                insert into project_members (id, workspace_id, project_id, user_id, role, status)
                values (?, ?, ?, ?, 'MEMBER', 'ACTIVE')
                """,
                UUID.randomUUID(), workspaceA, projectB, userA
        );

        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThatThrownBy(latest::migrate)
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("V13 tenant integrity check failed")
                .hasStackTraceContaining("project_members");
    }

    private void insertUser(JdbcTemplate jdbc, UUID userId, String email) {
        jdbc.update(
                "insert into users (id, email, password_hash, display_name) values (?, ?, ?, ?)",
                userId, email, "test-password-hash", email
        );
    }

    private void insertWorkspace(JdbcTemplate jdbc, UUID workspaceId, UUID ownerId, String slug) {
        jdbc.update(
                "insert into workspaces (id, owner_user_id, name, slug) values (?, ?, ?, ?)",
                workspaceId, ownerId, slug, slug
        );
    }
}
