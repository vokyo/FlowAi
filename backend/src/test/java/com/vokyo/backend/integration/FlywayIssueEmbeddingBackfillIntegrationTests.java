package com.vokyo.backend.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayIssueEmbeddingBackfillIntegrationTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres")
            );

    @Test
    void v20QueuesAnEmbeddingForEveryIssueThatAlreadyExists() {
        flyway(MigrationVersion.fromVersion("19")).migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID state = UUID.randomUUID();
        jdbc.update(
                "insert into users (id, email, password_hash, display_name) values (?, ?, ?, ?)",
                user, "backfill@example.com", "test-password-hash", "Backfill"
        );
        jdbc.update(
                "insert into workspaces (id, owner_user_id, name, slug) values (?, ?, ?, ?)",
                workspace, user, "Backfill", "backfill"
        );
        jdbc.update(
                "insert into projects (id, workspace_id, created_by_user_id, name) values (?, ?, ?, ?)",
                project, workspace, user, "Backfill project"
        );
        jdbc.update(
                """
                insert into project_workflow_states (id, workspace_id, project_id, name, category, position)
                values (?, ?, ?, 'Todo', 'TODO', 10000)
                """,
                state, workspace, project
        );
        UUID issue = UUID.randomUUID();
        jdbc.update(
                """
                insert into issues
                    (id, workspace_id, project_id, created_by_user_id, title, workflow_state_id, board_position)
                values (?, ?, ?, ?, 'Existing issue', ?, 10000)
                """,
                issue, workspace, project, user, state
        );

        flyway(MigrationVersion.LATEST).migrate();

        assertThat(jdbc.queryForList("select issue_id from issue_embedding_jobs", UUID.class))
                .containsExactly(issue);
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
