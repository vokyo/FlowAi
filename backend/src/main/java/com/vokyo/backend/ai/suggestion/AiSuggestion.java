package com.vokyo.backend.ai.suggestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ai_suggestions")
public class AiSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_issue_id")
    private Issue sourceIssue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AiSuggestionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiSuggestionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode content;

    @Column(name = "prompt_version", nullable = false, length = 100)
    private String promptVersion;

    @Column(length = 80)
    private String provider;

    @Column(length = 160)
    private String model;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "apply_idempotency_key")
    private UUID applyIdempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    /*
     * JPA/Hibernate 需要无参数构造函数。
     * protected 可以防止业务代码创建不完整 Entity。
     */
    protected AiSuggestion() {
    }

    public AiSuggestion(
            Workspace workspace,
            Project project,
            Issue sourceIssue,
            User createdByUser,
            AiSuggestionType type,
            JsonNode content,
            String promptVersion,
            String provider,
            String model,
            String inputHash,
            Integer inputTokens,
            Integer outputTokens,
            Instant expiresAt
    ) {
        this.workspace = Objects.requireNonNull(
                workspace,
                "workspace is required"
        );
        this.project = Objects.requireNonNull(
                project,
                "project is required"
        );
        this.createdByUser = Objects.requireNonNull(
                createdByUser,
                "createdByUser is required"
        );
        this.type = Objects.requireNonNull(
                type,
                "type is required"
        );
        this.sourceIssue = sourceIssue;
        this.content = copyAndValidateContent(content);
        this.promptVersion = requireText(
                promptVersion,
                "promptVersion"
        );
        this.provider = normalizeOptionalText(provider);
        this.model = normalizeOptionalText(model);
        this.inputHash = validateInputHash(inputHash);
        this.inputTokens = validateTokenCount(
                inputTokens,
                "inputTokens"
        );
        this.outputTokens = validateTokenCount(
                outputTokens,
                "outputTokens"
        );
        this.expiresAt = Objects.requireNonNull(
                expiresAt,
                "expiresAt is required"
        );

        validateSourceIssue(type, sourceIssue);

        this.status = AiSuggestionStatus.DRAFT;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();

        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException(
                    "Suggestion expiry must be after creation time"
            );
        }

        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now is required");

        return status == AiSuggestionStatus.DRAFT
                && !expiresAt.isAfter(now);
    }

    public void expire(Instant now) {
        Objects.requireNonNull(now, "now is required");
        requireDraft();

        if (expiresAt.isAfter(now)) {
            throw new IllegalStateException(
                    "Suggestion cannot expire before expiresAt"
            );
        }

        this.status = AiSuggestionStatus.EXPIRED;
        this.updatedAt = now;
    }

    public void dismiss(Instant now) {
        Objects.requireNonNull(now, "now is required");
        requireDraft();

        this.status = AiSuggestionStatus.DISMISSED;
        this.dismissedAt = now;
        this.updatedAt = now;
    }

    public void apply(UUID idempotencyKey, Instant now) {
        Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey is required"
        );
        Objects.requireNonNull(now, "now is required");

        /*
         * 相同 idempotency key 的重复请求视为同一次 Apply。
         */
        if (status == AiSuggestionStatus.APPLIED
                && idempotencyKey.equals(applyIdempotencyKey)) {
            return;
        }

        requireDraft();

        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException(
                    "Expired suggestion cannot be applied"
            );
        }

        this.status = AiSuggestionStatus.APPLIED;
        this.applyIdempotencyKey = idempotencyKey;
        this.appliedAt = now;
        this.updatedAt = now;
    }

    public boolean wasAppliedWith(UUID idempotencyKey) {
        return status == AiSuggestionStatus.APPLIED
                && Objects.equals(
                applyIdempotencyKey,
                idempotencyKey
        );
    }

    private void requireDraft() {
        if (status != AiSuggestionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Suggestion must be in DRAFT status"
            );
        }
    }

    private static void validateSourceIssue(
            AiSuggestionType type,
            Issue sourceIssue
    ) {
        boolean issueSuggestion =
                type == AiSuggestionType.ISSUE_BREAKDOWN
                        || type == AiSuggestionType.ISSUE_SUMMARY;

        if (issueSuggestion && sourceIssue == null) {
            throw new IllegalArgumentException(
                    "Issue suggestion requires a source issue"
            );
        }

        if (type == AiSuggestionType.PROJECT_SUMMARY
                && sourceIssue != null) {
            throw new IllegalArgumentException(
                    "Project summary cannot have a source issue"
            );
        }
    }

    private static JsonNode copyAndValidateContent(JsonNode content) {
        Objects.requireNonNull(content, "content is required");

        if (!content.isObject()) {
            throw new IllegalArgumentException(
                    "Suggestion content must be a JSON object"
            );
        }

        return content.deepCopy();
    }

    private static String validateInputHash(String inputHash) {
        String normalized = requireText(inputHash, "inputHash");

        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "inputHash must be a lowercase SHA-256 hex value"
            );
        }

        return normalized;
    }

    private static Integer validateTokenCount(
            Integer tokenCount,
            String fieldName
    ) {
        if (tokenCount != null && tokenCount < 0) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be negative"
            );
        }

        return tokenCount;
    }

    private static String requireText(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required"
            );
        }

        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public Project getProject() {
        return project;
    }

    public Issue getSourceIssue() {
        return sourceIssue;
    }

    public User getCreatedByUser() {
        return createdByUser;
    }

    public AiSuggestionType getType() {
        return type;
    }

    public AiSuggestionStatus getStatus() {
        return status;
    }

    public JsonNode getContent() {
        return content.deepCopy();
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getInputHash() {
        return inputHash;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public UUID getApplyIdempotencyKey() {
        return applyIdempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }
}