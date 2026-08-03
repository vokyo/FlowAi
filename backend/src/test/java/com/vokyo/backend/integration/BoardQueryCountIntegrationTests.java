package com.vokyo.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every association on Issue is lazy and IssueMapper reads all of them, so without
 * batched fetching the board pays one label query per issue and one user query per
 * distinct participant. A full column is 50 issues, and a board loads every column,
 * which is enough for that to dominate the request.
 *
 * The assertion is deliberately a ceiling rather than an exact number: it should
 * survive an extra query being added on purpose, and fail the moment the cost
 * starts scaling with the number of issues.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=dummy",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class BoardQueryCountIntegrationTests extends AbstractMockMvcIntegrationTest {

    private static final int ISSUES_IN_COLUMN = 50;

    /**
     * The board currently costs 10 statements at this size. The ceiling leaves room
     * for a deliberate extra read while staying far below the 59 this cost before
     * the associations were batched.
     */
    private static final int MAX_BOARD_STATEMENTS = 20;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void loadingAFullBoardDoesNotScaleItsQueryCountWithIssueCount() throws Exception {
        String accessToken = register();
        String projectId = createProject(accessToken);
        JsonNode seedIssue = createIssue(accessToken, projectId, "Board issue 1");
        UUID workspaceId = jdbcTemplate.queryForObject(
                "select workspace_id from issues where id = ?::uuid",
                UUID.class,
                seedIssue.get("id").asText()
        );
        UUID creatorId = UUID.fromString(seedIssue.get("creator").get("id").asText());
        UUID workflowStateId = UUID.fromString(seedIssue.get("workflowState").get("id").asText());
        UUID labelId = createLabel(workspaceId, UUID.fromString(projectId));
        attachLabel(labelId, UUID.fromString(seedIssue.get("id").asText()));

        for (int index = 2; index <= ISSUES_IN_COLUMN; index++) {
            UUID issueId = insertIssue(
                    workspaceId,
                    UUID.fromString(projectId),
                    creatorId,
                    workflowStateId,
                    "Board issue " + index,
                    index * 10_000L
            );
            attachLabel(labelId, issueId);
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        JsonNode board = readJson(mockMvc.perform(get("/api/issues/board")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk()));

        assertThat(findColumn(board, workflowStateId.toString()).get("issues"))
                .hasSize(ISSUES_IN_COLUMN);
        assertThat(statistics.getPrepareStatementCount())
                .as(
                        "a board of %s issues should not cost a query per issue",
                        ISSUES_IN_COLUMN
                )
                .isLessThanOrEqualTo(MAX_BOARD_STATEMENTS);
    }

    private UUID insertIssue(
            UUID workspaceId,
            UUID projectId,
            UUID creatorId,
            UUID workflowStateId,
            String title,
            long boardPosition
    ) {
        UUID issueId = UUID.randomUUID();
        Timestamp timestamp = Timestamp.from(Instant.parse("2026-07-14T08:00:00Z"));
        jdbcTemplate.update(
                """
                insert into issues (
                    id, workspace_id, project_id, created_by_user_id, title, description,
                    workflow_state_id, status, priority, created_at, updated_at, board_position
                ) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, null, ?::uuid, 'TODO', 'LOW', ?, ?, ?)
                """,
                issueId,
                workspaceId,
                projectId,
                creatorId,
                title,
                workflowStateId,
                timestamp,
                timestamp,
                boardPosition
        );
        return issueId;
    }

    private UUID createLabel(UUID workspaceId, UUID projectId) {
        UUID labelId = UUID.randomUUID();
        Timestamp timestamp = Timestamp.from(Instant.parse("2026-07-14T08:00:00Z"));
        jdbcTemplate.update(
                """
                insert into project_labels (
                    id, workspace_id, project_id, name, color, created_at, updated_at
                ) values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)
                """,
                labelId,
                workspaceId,
                projectId,
                "Batched",
                "#64748b",
                timestamp,
                timestamp
        );
        return labelId;
    }

    private void attachLabel(UUID labelId, UUID issueId) {
        jdbcTemplate.update(
                "insert into issue_labels (issue_id, label_id) values (?::uuid, ?::uuid)",
                issueId,
                labelId
        );
    }

    private String register() throws Exception {
        JsonNode response = readJson(postJson(
                "/api/auth/register",
                """
                {
                  "email": "board-count-%s@example.com",
                  "password": "password123",
                  "displayName": "Board Count User",
                  "workspaceName": "Board Count Workspace"
                }
                """.formatted(uniqueId()),
                null
        ).andExpect(status().isOk()));
        return response.get("accessToken").asText();
    }

    private String createProject(String accessToken) throws Exception {
        JsonNode response = readJson(postJson(
                "/api/projects",
                "{\"name\":\"Board count project\"}",
                accessToken
        ).andExpect(status().isOk()));
        return response.get("id").asText();
    }

    private JsonNode createIssue(String accessToken, String projectId, String title) throws Exception {
        return readJson(postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "%s",
                  "priority": "HIGH"
                }
                """.formatted(projectId, title),
                accessToken
        ).andExpect(status().isOk()));
    }

    private JsonNode findColumn(JsonNode board, String workflowStateId) {
        for (JsonNode column : board.get("columns")) {
            if (workflowStateId.equals(column.at("/workflowState/id").asText())) {
                return column;
            }
        }
        throw new AssertionError("Board column not found: " + workflowStateId);
    }
}
