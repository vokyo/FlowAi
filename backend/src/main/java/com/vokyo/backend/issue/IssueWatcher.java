package com.vokyo.backend.issue;

import com.vokyo.backend.issue.dto.IssueWatchResponse;
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
