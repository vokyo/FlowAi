package com.vokyo.backend.issue;

import com.vokyo.backend.project.Project;
import com.vokyo.backend.project.ProjectLabel;
import com.vokyo.backend.project.ProjectWorkflowState;
import com.vokyo.backend.project.WorkflowStateCategory;
import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.Workspace;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
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

    @ManyToMany
    @JoinTable(
            name = "issue_labels",
            joinColumns = @JoinColumn(name = "issue_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id")
    )
    @OrderBy("name asc")
    private Set<ProjectLabel> labels = new LinkedHashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    private User assigneeUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_state_id", nullable = false)
    private ProjectWorkflowState workflowState;

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

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "board_position", nullable = false)
    private long boardPosition;

    protected Issue() {
    }

    public Issue(
            Workspace workspace,
            Project project,
            User createdByUser,
            String title,
            String description,
            User assigneeUser,
            ProjectWorkflowState workflowState,
            IssuePriority priority,
            LocalDate dueDate,
            long boardPosition
    ) {
        this.workspace = workspace;
        this.project = project;
        this.createdByUser = createdByUser;
        this.title = title;
        this.description = description;
        this.assigneeUser = assigneeUser;
        this.workflowState = workflowState;
        this.status = statusFromWorkflowState(workflowState);
        this.completedAt = isDone(workflowState) ? Instant.now() : null;
        this.priority = priority;
        this.dueDate = dueDate;
        this.boardPosition = boardPosition;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (this.status == null && this.workflowState != null) {
            this.status = statusFromWorkflowState(this.workflowState);
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

    public Set<ProjectLabel> getLabels() {
        return labels;
    }

    public User getAssigneeUser() {
        return assigneeUser;
    }

    public IssueStatus getStatus() {
        return archivedAt == null ? status : IssueStatus.ARCHIVED;
    }

    public ProjectWorkflowState getWorkflowState() {
        return workflowState;
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

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getBoardPosition() {
        return boardPosition;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void replaceLabels(Collection<ProjectLabel> labels) {
        this.labels.clear();
        this.labels.addAll(labels);
    }

    public void assignTo(User assigneeUser) {
        this.assigneeUser = assigneeUser;
    }

    public void changeWorkflowState(ProjectWorkflowState workflowState) {
        boolean wasDone = isDone(this.workflowState);
        this.workflowState = workflowState;
        if (this.archivedAt == null) {
            this.status = statusFromWorkflowState(workflowState);
            synchronizeCompletion(wasDone);
        }
    }

    public void archive() {
        if (this.archivedAt == null) {
            this.archivedAt = Instant.now();
        }
        this.status = IssueStatus.ARCHIVED;
    }

    public void unarchive() {
        this.archivedAt = null;
        this.status = statusFromWorkflowState(this.workflowState);
        synchronizeCompletion(isDone(this.workflowState));
    }

    public void changePriority(IssuePriority priority) {
        this.priority = priority;
    }

    public void changeDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public void moveOnBoard(long boardPosition) {
        this.boardPosition = boardPosition;
    }

    private IssueStatus statusFromWorkflowState(ProjectWorkflowState workflowState) {
        if (workflowState == null) {
            return IssueStatus.TODO;
        }

        return workflowState.getCategory().toIssueStatus();
    }

    private void synchronizeCompletion(boolean wasDone) {
        if (isDone(this.workflowState)) {
            if (!wasDone || this.completedAt == null) {
                this.completedAt = Instant.now();
            }
            return;
        }

        this.completedAt = null;
    }

    private boolean isDone(ProjectWorkflowState workflowState) {
        return workflowState != null
                && workflowState.getCategory() == WorkflowStateCategory.DONE;
    }
}
