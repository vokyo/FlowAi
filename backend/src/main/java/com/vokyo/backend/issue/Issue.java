package com.vokyo.backend.issue;

import com.vokyo.backend.project.Project;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "issues")
public class Issue {

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
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    private User assigneeUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IssueStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private IssuePriority priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    protected Issue() {
    }

    public Issue(
            Workspace workspace,
            Project project,
            User createdByUser,
            String title,
            String description,
            User assigneeUser,
            IssueStatus status,
            IssuePriority priority,
            LocalDate dueDate
    ) {
        this.workspace = workspace;
        this.project = project;
        this.createdByUser = createdByUser;
        this.title = title;
        this.description = description;
        this.assigneeUser = assigneeUser;
        this.status = status == null ? IssueStatus.TODO : status;
        this.priority = priority;
        this.dueDate = dueDate;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (this.status == null) {
            this.status = IssueStatus.TODO;
        }
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

    public User getCreatedByUser() {
        return createdByUser;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public User getAssigneeUser() {
        return assigneeUser;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public IssuePriority getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void assignTo(User assigneeUser) {
        this.assigneeUser = assigneeUser;
    }

    public void changeStatus(IssueStatus status) {
        this.status = status;
    }

    public void changePriority(IssuePriority priority) {
        this.priority = priority;
    }

    public void changeDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }
}
