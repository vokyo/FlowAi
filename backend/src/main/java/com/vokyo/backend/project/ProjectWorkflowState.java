package com.vokyo.backend.project;

import com.vokyo.backend.workspace.Workspace;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_workflow_states")
public class ProjectWorkflowState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkflowStateCategory category;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectWorkflowState() {
    }

    public ProjectWorkflowState(
            Workspace workspace,
            Project project,
            String name,
            WorkflowStateCategory category,
            int position
    ) {
        this.workspace = workspace;
        this.project = project;
        this.name = name;
        this.category = category;
        this.position = position;
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

    public String getName() {
        return name;
    }

    public WorkflowStateCategory getCategory() {
        return category;
    }

    public int getPosition() {
        return position;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeCategory(WorkflowStateCategory category) {
        this.category = category;
    }

    public void moveTo(int position) {
        this.position = position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
