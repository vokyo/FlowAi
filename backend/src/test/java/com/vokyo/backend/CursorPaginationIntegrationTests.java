package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class CursorPaginationIntegrationTests extends AbstractMockMvcIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void issueCursorIsContinuousForTiedTimesAndBoundToFilters() throws Exception {
        Session session = register();
        String projectId = createProject(session, "Cursor project");
        for (int index = 1; index <= 5; index++) {
            createIssue(session, projectId, "Page issue " + index);
        }

        jdbcTemplate.update(
                "update issues set created_at = ? where project_id = ?::uuid",
                Timestamp.from(Instant.parse("2026-07-14T08:00:00Z")),
                projectId
        );

        JsonNode first = listIssues(session, projectId, "Page", null, 2);
        JsonNode second = listIssues(session, projectId, "Page", first.get("nextCursor").asText(), 2);
        JsonNode third = listIssues(session, projectId, "Page", second.get("nextCursor").asText(), 2);

        assertThat(first.get("items")).hasSize(2);
        assertThat(second.get("items")).hasSize(2);
        assertThat(third.get("items")).hasSize(1);
        assertThat(third.get("nextCursor").isNull()).isTrue();

        List<String> ids = new ArrayList<>();
        first.get("items").forEach(issue -> ids.add(issue.get("id").asText()));
        second.get("items").forEach(issue -> ids.add(issue.get("id").asText()));
        third.get("items").forEach(issue -> ids.add(issue.get("id").asText()));
        assertThat(ids).hasSize(5);
        assertThat(new HashSet<>(ids)).hasSize(5);

        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .queryParam("q", "different-filter")
                        .queryParam("cursor", first.get("nextCursor").asText())
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .queryParam("cursor", "not+a+cursor=")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isBadRequest());

        for (int invalidLimit : List.of(0, 101)) {
            mockMvc.perform(get("/api/issues")
                            .queryParam("projectId", projectId)
                            .queryParam("limit", Integer.toString(invalidLimit))
                            .header("Authorization", bearer(session.accessToken())))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/issues")
                        .queryParam("projectId", projectId)
                        .queryParam("limit", "100")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk());
    }

    @Test
    void boardLoadsFiftyPerColumnAndContinuesByWorkflowState() throws Exception {
        Session session = register();
        String projectId = createProject(session, "Board cursor project");
        JsonNode firstIssue = createIssue(session, projectId, "Board issue 1");
        UUID workspaceId = jdbcTemplate.queryForObject(
                "select workspace_id from issues where id = ?::uuid",
                UUID.class,
                firstIssue.get("id").asText()
        );
        UUID creatorId = UUID.fromString(firstIssue.get("creator").get("id").asText());
        UUID workflowStateId = UUID.fromString(firstIssue.get("workflowState").get("id").asText());
        Instant createdAt = Instant.parse("2026-07-14T08:00:00Z");

        for (int index = 2; index <= 51; index++) {
            jdbcTemplate.update(
                    """
                    insert into issues (
                        id, workspace_id, project_id, created_by_user_id, title, description,
                        workflow_state_id, status, priority, created_at, updated_at, board_position
                    ) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, null, ?::uuid, 'TODO', 'LOW', ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    workspaceId,
                    UUID.fromString(projectId),
                    creatorId,
                    "Board issue " + index,
                    workflowStateId,
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt),
                    index * 10_000L
            );
        }

        JsonNode board = readJson(mockMvc.perform(get("/api/issues/board")
                        .queryParam("projectId", projectId)
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()));
        JsonNode todoColumn = findColumn(board, workflowStateId.toString());
        assertThat(todoColumn.get("issues")).hasSize(50);
        assertThat(todoColumn.get("nextCursor").asText()).isNotBlank();

        JsonNode remainder = readJson(mockMvc.perform(get(
                                "/api/issues/board/states/{workflowStateId}",
                                workflowStateId
                        )
                        .queryParam("projectId", projectId)
                        .queryParam("cursor", todoColumn.get("nextCursor").asText())
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()));
        assertThat(remainder.get("items")).hasSize(1);
        assertThat(remainder.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void commentAndActivityPagesAreNewestFirstAndContinuous() throws Exception {
        Session session = register();
        String projectId = createProject(session, "Timeline cursor project");
        JsonNode issue = createIssue(session, projectId, "Timeline issue");
        String issueId = issue.get("id").asText();
        for (int index = 1; index <= 3; index++) {
            mockMvc.perform(post("/api/issues/{issueId}/comments", issueId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"Comment " + index + "\"}")
                            .header("Authorization", bearer(session.accessToken())))
                    .andExpect(status().isOk());
        }

        JsonNode commentsFirst = readJson(mockMvc.perform(get("/api/issues/{issueId}/comments", issueId)
                        .queryParam("limit", "2")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()));
        JsonNode commentsSecond = readJson(mockMvc.perform(get("/api/issues/{issueId}/comments", issueId)
                        .queryParam("limit", "2")
                        .queryParam("cursor", commentsFirst.get("nextCursor").asText())
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()));
        assertThat(commentsFirst.at("/items/0/body").asText()).isEqualTo("Comment 3");
        assertThat(commentsFirst.get("items")).hasSize(2);
        assertThat(commentsSecond.get("items")).hasSize(1);

        JsonNode activities = readJson(mockMvc.perform(get("/api/issues/{issueId}/activities", issueId)
                        .queryParam("limit", "3")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()));
        assertThat(activities.get("items")).hasSize(3);
        assertThat(activities.at("/items/0/eventType").asText()).isEqualTo("COMMENT_CREATED");
        assertThat(activities.get("nextCursor").asText()).isNotBlank();
    }

    @Test
    void workspaceInvitationPagesAreContinuousAndRejectInvalidCursor() throws Exception {
        Session session = register();
        for (int index = 1; index <= 3; index++) {
            postJson(
                    "/api/workspaces/current/invitations",
                    """
                    {
                      "email": "invite-%s-%s@example.com",
                      "role": "MEMBER"
                    }
                    """.formatted(index, uniqueId()),
                    session.accessToken()
            ).andExpect(status().isOk());
        }

        JsonNode first = readJson(mockMvc.perform(get("/api/workspaces/current/invitations")
                        .queryParam("limit", "2")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()));
        JsonNode second = readJson(mockMvc.perform(get("/api/workspaces/current/invitations")
                        .queryParam("limit", "2")
                        .queryParam("cursor", first.get("nextCursor").asText())
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()));

        assertThat(first.get("items")).hasSize(2);
        assertThat(second.get("items")).hasSize(1);
        Set<String> invitationIds = new HashSet<>();
        first.get("items").forEach(invitation -> invitationIds.add(invitation.get("id").asText()));
        second.get("items").forEach(invitation -> invitationIds.add(invitation.get("id").asText()));
        assertThat(invitationIds).hasSize(3);
        assertThat(second.get("nextCursor").isNull()).isTrue();

        mockMvc.perform(get("/api/workspaces/current/invitations")
                        .queryParam("cursor", "invalid")
                        .header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isBadRequest());
    }

    private Session register() throws Exception {
        JsonNode response = readJson(postJson(
                "/api/auth/register",
                """
                {
                  "email": "cursor-%s@example.com",
                  "password": "password123",
                  "displayName": "Cursor User",
                  "workspaceName": "Cursor Workspace"
                }
                """.formatted(uniqueId()),
                null
        ).andExpect(status().isOk()));
        return new Session(response.get("accessToken").asText());
    }

    private String createProject(Session session, String name) throws Exception {
        JsonNode response = readJson(postJson(
                "/api/projects",
                "{\"name\":\"" + name + "\"}",
                session.accessToken()
        ).andExpect(status().isOk()));
        return response.get("id").asText();
    }

    private JsonNode createIssue(Session session, String projectId, String title) throws Exception {
        return readJson(postJson(
                "/api/issues",
                """
                {
                  "projectId": "%s",
                  "title": "%s",
                  "priority": "HIGH"
                }
                """.formatted(projectId, title),
                session.accessToken()
        ).andExpect(status().isOk()));
    }

    private JsonNode listIssues(
            Session session,
            String projectId,
            String query,
            String cursor,
            int limit
    ) throws Exception {
        var request = get("/api/issues")
                .queryParam("projectId", projectId)
                .queryParam("q", query)
                .queryParam("limit", Integer.toString(limit))
                .header("Authorization", bearer(session.accessToken()));
        if (cursor != null) {
            request.queryParam("cursor", cursor);
        }
        return readJson(mockMvc.perform(request).andExpect(status().isOk()));
    }

    private JsonNode findColumn(JsonNode board, String workflowStateId) {
        for (JsonNode column : board.get("columns")) {
            if (workflowStateId.equals(column.at("/workflowState/id").asText())) {
                return column;
            }
        }
        throw new AssertionError("Board column not found: " + workflowStateId);
    }

    private record Session(String accessToken) {
    }
}
