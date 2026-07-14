package com.vokyo.backend.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, UUID> {

    Optional<WorkspaceMembership> findByWorkspace_IdAndUser_Id(UUID workspaceId, UUID userId);

    List<WorkspaceMembership> findByWorkspace_Id(UUID workspaceId);

    List<WorkspaceMembership> findByUser_IdAndStatusOrderByLastAccessedAtDescJoinedAtAsc(
            UUID userId,
            MembershipStatus status
    );

    Optional<WorkspaceMembership> findByWorkspace_IdAndUser_IdAndStatus(
            UUID workspaceId,
            UUID userId,
            MembershipStatus status
    );

    boolean existsByWorkspace_IdAndUser_Id(UUID workspaceId, UUID userId);

    long countByWorkspace_IdAndRoleAndStatus(UUID workspaceId, WorkspaceRole role, MembershipStatus status);
}
