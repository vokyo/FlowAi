package com.vokyo.backend.issue;

import com.vokyo.backend.project.Project;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "issue_comments")
public class IssueComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User authorUser;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IssueComment() {
    }

    public IssueComment(Workspace workspace, Project project, Issue issue, User authorUser, String body) {
        this.workspace = workspace;
        this.project = project;
        this.issue = issue;
        this.authorUser = authorUser;
        this.body = body;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
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

    public User getAuthorUser() {
        return authorUser;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void editBody(String body) {
        this.body = body;
    }

    /**
     * Restates when this comment was written, for comments whose history predates
     * the row. Without it every comment on an imported or seeded issue reads as
     * posted the moment the row was written.
     */
    public void backdateTo(Instant createdAt) {
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (createdAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("createdAt cannot be in the future");
        }

        this.createdAt = createdAt;
    }
}
