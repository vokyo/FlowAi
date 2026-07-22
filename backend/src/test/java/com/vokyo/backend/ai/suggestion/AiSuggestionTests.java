package com.vokyo.backend.ai.suggestion;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.issue.IssuePriority;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSuggestionTests {

    private static final String INPUT_HASH = "a".repeat(64);
    private static final Instant EXPIRES_AT = Instant.parse("2099-01-08T00:00:00Z");

    private final Fixture fixture = new Fixture();

    @Test
    void createsDraftAndDefensivelyCopiesJsonContent() {
        ObjectNode content = JsonNodeFactory.instance.objectNode()
                .put("overview", "Initial overview");

        AiSuggestion suggestion = issueSuggestion(content);
        content.put("overview", "Mutated outside entity");

        assertThat(suggestion.getStatus()).isEqualTo(AiSuggestionStatus.DRAFT);
        assertThat(suggestion.getContent().get("overview").asText())
                .isEqualTo("Initial overview");

        ObjectNode returnedContent = (ObjectNode) suggestion.getContent();
        returnedContent.put("overview", "Mutated returned copy");

        assertThat(suggestion.getContent().get("overview").asText())
                .isEqualTo("Initial overview");
    }

    @Test
    void enforcesSuggestionSourceRulesAndObjectContent() {
        assertThatThrownBy(() -> new AiSuggestion(
                fixture.workspace,
                fixture.project,
                null,
                fixture.user,
                AiSuggestionType.ISSUE_BREAKDOWN,
                JsonNodeFactory.instance.objectNode(),
                "issue-breakdown-v1",
                "fake",
                "fake-model",
                INPUT_HASH,
                10,
                20,
                EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Issue suggestion requires a source issue");

        assertThatThrownBy(() -> new AiSuggestion(
                fixture.workspace,
                fixture.project,
                fixture.issue,
                fixture.user,
                AiSuggestionType.PROJECT_SUMMARY,
                JsonNodeFactory.instance.objectNode(),
                "project-summary-v1",
                "fake",
                "fake-model",
                INPUT_HASH,
                null,
                null,
                EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project summary cannot have a source issue");

        assertThatThrownBy(() -> new AiSuggestion(
                fixture.workspace,
                fixture.project,
                null,
                fixture.user,
                AiSuggestionType.PROJECT_SUMMARY,
                JsonNodeFactory.instance.arrayNode(),
                "project-summary-v1",
                "fake",
                "fake-model",
                INPUT_HASH,
                null,
                null,
                EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Suggestion content must be a JSON object");
    }

    @Test
    void transitionsDraftThroughDismissExpiryAndIdempotentApply() {
        Instant now = Instant.parse("2099-01-01T00:00:00Z");

        AiSuggestion dismissed = issueSuggestion(JsonNodeFactory.instance.objectNode());
        dismissed.dismiss(now);
        assertThat(dismissed.getStatus()).isEqualTo(AiSuggestionStatus.DISMISSED);
        assertThat(dismissed.getDismissedAt()).isEqualTo(now);
        assertThatThrownBy(() -> dismissed.dismiss(now.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Suggestion must be in DRAFT status");

        AiSuggestion expired = suggestionExpiringAt(now);
        assertThat(expired.isExpiredAt(now)).isTrue();
        expired.expire(now);
        assertThat(expired.getStatus()).isEqualTo(AiSuggestionStatus.EXPIRED);

        AiSuggestion applied = issueSuggestion(JsonNodeFactory.instance.objectNode());
        UUID key = UUID.randomUUID();
        UUID createdIssueId = UUID.randomUUID();
        applied.apply(key, List.of(createdIssueId), now);
        applied.apply(key, List.of(createdIssueId), now.plusSeconds(1));
        assertThat(applied.getStatus()).isEqualTo(AiSuggestionStatus.APPLIED);
        assertThat(applied.getApplyIdempotencyKey()).isEqualTo(key);
        assertThat(applied.wasAppliedWith(key)).isTrue();
        assertThat(applied.getCreatedIssueIds()).containsExactly(createdIssueId);
        assertThatThrownBy(() -> applied.apply(
                UUID.randomUUID(),
                List.of(UUID.randomUUID()),
                now.plusSeconds(2)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Suggestion must be in DRAFT status");
    }

    @Test
    void rejectsInvalidHashesAndTokenCounts() {
        assertThatThrownBy(() -> new AiSuggestion(
                fixture.workspace,
                fixture.project,
                fixture.issue,
                fixture.user,
                AiSuggestionType.ISSUE_SUMMARY,
                JsonNodeFactory.instance.objectNode(),
                "issue-summary-v1",
                null,
                null,
                "not-a-sha256",
                null,
                null,
                EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inputHash must be a lowercase SHA-256 hex value");

        assertThatThrownBy(() -> new AiSuggestion(
                fixture.workspace,
                fixture.project,
                fixture.issue,
                fixture.user,
                AiSuggestionType.ISSUE_SUMMARY,
                JsonNodeFactory.instance.objectNode(),
                "issue-summary-v1",
                null,
                null,
                INPUT_HASH,
                -1,
                null,
                EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inputTokens cannot be negative");
    }

    private AiSuggestion issueSuggestion(ObjectNode content) {
        return suggestion(content, EXPIRES_AT);
    }

    private AiSuggestion suggestionExpiringAt(Instant expiresAt) {
        return suggestion(JsonNodeFactory.instance.objectNode(), expiresAt);
    }

    private AiSuggestion suggestion(ObjectNode content, Instant expiresAt) {
        return new AiSuggestion(
                fixture.workspace,
                fixture.project,
                fixture.issue,
                fixture.user,
                AiSuggestionType.ISSUE_BREAKDOWN,
                content,
                "issue-breakdown-v1",
                "fake",
                "fake-model",
                INPUT_HASH,
                10,
                20,
                expiresAt
        );
    }

    private static final class Fixture {
        private final User user = new User("owner@example.com", "password-hash", "Owner");
        private final Workspace workspace = new Workspace(user, "Workspace", "workspace");
        private final Project project = new Project(workspace, user, "Project", "Description");
        private final ProjectWorkflowState workflowState = new ProjectWorkflowState(
                workspace,
                project,
                "Todo",
                WorkflowStateCategory.TODO,
                0
        );
        private final Issue issue = new Issue(
                workspace,
                project,
                user,
                "Issue",
                "Description",
                null,
                workflowState,
                IssuePriority.MEDIUM,
                null,
                0L
        );
    }
}
