package com.vokyo.backend.workspace;

import com.vokyo.backend.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "workspace_memberships",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_workspace_memberships_workspace_user",
                        columnNames = {"workspace_id", "user_id"}
                )
        }
)
public class WorkspaceMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkspaceRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MembershipStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkspaceMembership() {
    }

    public WorkspaceMembership(Workspace workspace, User user, WorkspaceRole role) {
        this.workspace = workspace;
        this.user = user;
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.joinedAt = now;
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

    public User getUser() {
        return user;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void changeRole(WorkspaceRole role) {
        this.role = role;
    }

    public void disable() {
        this.status = MembershipStatus.DISABLED;
    }
}