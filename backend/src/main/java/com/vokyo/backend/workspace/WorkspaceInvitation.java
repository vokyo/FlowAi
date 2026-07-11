package com.vokyo.backend.workspace;

import com.vokyo.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_invitations")
public class WorkspaceInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_user_id", nullable = false)
    private User invitedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by_user_id")
    private User acceptedByUser;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkspaceRole role;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkspaceInvitationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkspaceInvitation() {
    }

    public WorkspaceInvitation(
            Workspace workspace,
            User invitedByUser,
            String email,
            WorkspaceRole role,
            String tokenHash,
            Instant expiresAt
    ) {
        this.workspace = workspace;
        this.invitedByUser = invitedByUser;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.status = WorkspaceInvitationStatus.PENDING;
        this.expiresAt = expiresAt;
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

    public User getInvitedByUser() {
        return invitedByUser;
    }

    public User getAcceptedByUser() {
        return acceptedByUser;
    }

    public String getEmail() {
        return email;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public WorkspaceInvitationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isExpired(Instant now) {
        return status == WorkspaceInvitationStatus.PENDING && !expiresAt.isAfter(now);
    }

    public void reissue(String tokenHash, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public void accept(User user, Instant now) {
        this.acceptedByUser = user;
        this.acceptedAt = now;
        this.status = WorkspaceInvitationStatus.ACCEPTED;
    }

    public void revoke(Instant now) {
        if (this.status == WorkspaceInvitationStatus.PENDING) {
            this.status = WorkspaceInvitationStatus.REVOKED;
            this.revokedAt = now;
        }
    }
}
