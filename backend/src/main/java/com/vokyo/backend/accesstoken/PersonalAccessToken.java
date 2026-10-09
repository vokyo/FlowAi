package com.vokyo.backend.accesstoken;

import com.vokyo.backend.user.User;
import com.vokyo.backend.workspace.WorkspaceMembership;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A token an AI app presents to the MCP endpoint on a user's behalf. Updates write
 * only the columns that changed: recording a use and revoking can happen at the
 * same time, and writing every column would let one put back what the other changed,
 * such as a use un-revoking the token.
 */
@Entity
@DynamicUpdate
@Table(name = "personal_access_tokens")
public class PersonalAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_membership_id", nullable = false)
    private WorkspaceMembership workspaceMembership;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PersonalAccessToken() {
    }

    public PersonalAccessToken(
            User user,
            WorkspaceMembership workspaceMembership,
            String name,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.user = user;
        this.workspaceMembership = workspaceMembership;
        this.name = name;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** Records a use, but at most once per interval, so a busy token is not a write per request. */
    public void markUsed(Instant now, Duration interval) {
        if (lastUsedAt == null || !lastUsedAt.plus(interval).isAfter(now)) {
            lastUsedAt = now;
        }
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public WorkspaceMembership getWorkspaceMembership() {
        return workspaceMembership;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
