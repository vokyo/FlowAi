package com.vokyo.backend.workspace;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {

    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

    Optional<WorkspaceInvitation> findByWorkspace_IdAndEmailAndStatus(
            UUID workspaceId,
            String email,
            WorkspaceInvitationStatus status
    );

    List<WorkspaceInvitation> findByWorkspace_IdOrderByCreatedAtDesc(UUID workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from WorkspaceInvitation invitation where invitation.tokenHash = :tokenHash")
    Optional<WorkspaceInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation
            from WorkspaceInvitation invitation
            where invitation.workspace.id = :workspaceId
              and invitation.id = :invitationId
            """)
    Optional<WorkspaceInvitation> findByWorkspaceIdAndIdForUpdate(
            @Param("workspaceId") UUID workspaceId,
            @Param("invitationId") UUID invitationId
    );
}
