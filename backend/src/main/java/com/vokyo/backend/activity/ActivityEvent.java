package com.vokyo.backend.activity;

import com.vokyo.backend.issue.Issue;
import com.vokyo.backend.project.Project;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "activity_events")
public class ActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id")
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actorUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 60)
    private ActivityEventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ActivityEvent() {
    }

    public ActivityEvent(
            Workspace workspace,
            Project project,
            Issue issue,
            User actorUser,
            ActivityEventType eventType,
            Map<String, Object> metadata
    ) {
        this.workspace = workspace;
        this.project = project;
        this.issue = issue;
        this.actorUser = actorUser;
        this.eventType = eventType;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();

        if (this.metadata == null) {
            this.metadata = new LinkedHashMap<>();
        }
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

    public Issue getIssue() {
        return issue;
    }

    public User getActorUser() {
        return actorUser;
    }

    public ActivityEventType getEventType() {
        return eventType;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
