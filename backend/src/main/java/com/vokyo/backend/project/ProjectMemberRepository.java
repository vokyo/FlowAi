package com.vokyo.backend.project;

import com.vokyo.backend.workspace.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    @Query("""
            select member
            from ProjectMember member
            join fetch member.project project
            where member.workspace.id = :workspaceId
              and member.user.id = :userId
              and member.status = :status
            order by project.createdAt asc
            """)
    List<ProjectMember> findAccessibleProjectMemberships(
            @Param("workspaceId") UUID workspaceId,
            @Param("userId") UUID userId,
            @Param("status") MembershipStatus status
    );

    boolean existsByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
            UUID workspaceId,
            UUID projectId,
            UUID userId,
            MembershipStatus status
    );

    Optional<ProjectMember> findByWorkspace_IdAndProject_IdAndUser_IdAndStatus(
            UUID workspaceId,
            UUID projectId,
            UUID userId,
            MembershipStatus status
    );

    boolean existsByProject_IdAndUser_Id(UUID projectId, UUID userId);

    @Query("""
            select member
            from ProjectMember member
            join fetch member.user user
            where member.workspace.id = :workspaceId
              and member.project.id = :projectId
            order by member.joinedAt asc
            """)
    List<ProjectMember> findByProjectForMemberList(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId
    );
}
