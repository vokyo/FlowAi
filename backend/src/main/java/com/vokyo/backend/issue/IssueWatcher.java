package com.vokyo.backend.issue;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@IdClass(IssueWatcherId.class)
@Table(name = "issue_watchers")
public class IssueWatcher {
    @Id
    private UUID issueId;
    @Id
    private UUID userId;

    /**
     * Filled by the set_issue_watchers_tenant_scope trigger from the issue itself,
     * so the application can never write a row whose tenant disagrees with its
     * issue. Mapped read-only and deliberately without accessors: on a freshly
     * saved instance they are still null, because Hibernate does not read them
     * back after the insert.
     */
    @Column(name = "workspace_id", insertable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "project_id", insertable = false, updatable = false)
    private UUID projectId;

    public IssueWatcher() {
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getUserId() {
        return userId;
    }

    public IssueWatcher(UUID issueId, UUID userId) {
        this.issueId = issueId;
        this.userId = userId;
    }
}
