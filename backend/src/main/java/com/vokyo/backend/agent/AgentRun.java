package com.vokyo.backend.agent;

import com.vokyo.backend.project.Project;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A planning run whose plan waits for review. The backend, not the agent, decides
 * what happens to it: which version is the latest, and whether it was approved or
 * cancelled. Its id is the run id the agent keeps its checkpoints under.
 */
@Entity
@Table(name = "agent_runs")
public class AgentRun implements Persistable<UUID> {

    /** The first version and up to four revisions; then the run must be approved or cancelled. */
    public static final int MAX_VERSIONS = 5;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Column(nullable = false, length = 500)
    private String goal;

    @Column(name = "generated_on", nullable = false)
    private LocalDate generatedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentRunState state;

    @Column(name = "latest_version", nullable = false)
    private int latestVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // The id is the agent's run id, assigned before the row exists, so whether the
    // row is new cannot be told from the id; without this, save() would merge.
    @Transient
    private boolean isNew;

    protected AgentRun() {
    }

    public AgentRun(
        UUID id,
        Workspace workspace,
        Project project,
        User createdByUser,
        String goal,
        LocalDate generatedOn,
        Instant now
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.workspace = Objects.requireNonNull(workspace, "workspace is required");
        this.project = Objects.requireNonNull(project, "project is required");
        this.createdByUser = Objects.requireNonNull(createdByUser, "createdByUser is required");
        this.goal = Objects.requireNonNull(goal, "goal is required");
        this.generatedOn = Objects.requireNonNull(generatedOn, "generatedOn is required");
        this.state = AgentRunState.REVIEWING;
        this.latestVersion = 1;
        this.createdAt = Objects.requireNonNull(now, "now is required");
        this.updatedAt = now;
        this.isNew = true;
    }

    public boolean hasRoomForAnotherVersion() {
        return latestVersion < MAX_VERSIONS;
    }

    /** Makes the next version the one under review. */
    public int addVersion(Instant now) {
        requireReviewing();
        if (!hasRoomForAnotherVersion()) {
            throw new IllegalStateException("A run has at most " + MAX_VERSIONS + " versions");
        }
        latestVersion++;
        updatedAt = Objects.requireNonNull(now, "now is required");
        return latestVersion;
    }

    public void approve(Instant now) {
        requireReviewing();
        state = AgentRunState.APPROVED;
        updatedAt = Objects.requireNonNull(now, "now is required");
    }

    public void cancel(Instant now) {
        requireReviewing();
        state = AgentRunState.CANCELLED;
        updatedAt = Objects.requireNonNull(now, "now is required");
    }

    private void requireReviewing() {
        if (state != AgentRunState.REVIEWING) {
            throw new IllegalStateException("Run is " + state + ", not under review");
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public Project getProject() {
        return project;
    }

    public User getCreatedByUser() {
        return createdByUser;
    }

    public String getGoal() {
        return goal;
    }

    public LocalDate getGeneratedOn() {
        return generatedOn;
    }

    public AgentRunState getState() {
        return state;
    }

    public int getLatestVersion() {
        return latestVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
