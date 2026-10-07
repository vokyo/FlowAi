package com.vokyo.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.vokyo.backend.ai.suggestion.AiSuggestion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One version of a run's plan. An approvable version is applied through its draft
 * suggestion; one that is not says why, so the user can revise it instead.
 */
@Entity
@Table(name = "agent_plan_versions")
public class AgentPlanVersion {

    static final int MAX_REASON_LENGTH = 1_000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "rejection_reason", length = MAX_REASON_LENGTH)
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggestion_id")
    private AiSuggestion suggestion;

    @Column(name = "checkpoint_id", length = 100)
    private String checkpointId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentPlanVersion() {
    }

    private AgentPlanVersion(
        AgentRun run,
        int version,
        JsonNode content,
        AiSuggestion suggestion,
        String rejectionReason,
        String checkpointId,
        Instant now
    ) {
        this.run = Objects.requireNonNull(run, "run is required");
        this.version = version;
        this.content = Objects.requireNonNull(content, "content is required").deepCopy();
        this.contentHash = PlanContentHash.of(content);
        this.suggestion = suggestion;
        this.rejectionReason = rejectionReason;
        this.checkpointId = checkpointId;
        this.createdAt = Objects.requireNonNull(now, "now is required");
    }

    public static AgentPlanVersion approvable(
        AgentRun run,
        int version,
        JsonNode content,
        AiSuggestion suggestion,
        String checkpointId,
        Instant now
    ) {
        return new AgentPlanVersion(
            run, version, content, Objects.requireNonNull(suggestion, "suggestion is required"),
            null, checkpointId, now
        );
    }

    public static AgentPlanVersion notApprovable(
        AgentRun run,
        int version,
        JsonNode content,
        String reason,
        String checkpointId,
        Instant now
    ) {
        return new AgentPlanVersion(run, version, content, null, shorten(reason), checkpointId, now);
    }

    public boolean isApprovable() {
        return rejectionReason == null;
    }

    /** Found unapprovable when it was approved: the project changed since it was planned. */
    public void markNotApprovable(String reason) {
        this.rejectionReason = shorten(reason);
    }

    private static String shorten(String reason) {
        String text = Objects.requireNonNull(reason, "reason is required").strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH - 1) + "…";
    }

    public UUID getId() {
        return id;
    }

    public AgentRun getRun() {
        return run;
    }

    public int getVersion() {
        return version;
    }

    public JsonNode getContent() {
        return content.deepCopy();
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public AiSuggestion getSuggestion() {
        return suggestion;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
