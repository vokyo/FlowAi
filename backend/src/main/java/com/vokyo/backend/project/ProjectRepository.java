package com.vokyo.backend.project;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByWorkspace_IdOrderByCreatedAtAsc(UUID workspaceId);

    Optional<Project> findByIdAndWorkspace_Id(UUID id, UUID workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select project
            from Project project
            where project.id = :projectId
              and project.workspace.id = :workspaceId
            """)
    Optional<Project> findByIdAndWorkspaceIdForUpdate(
            @Param("projectId") UUID projectId,
            @Param("workspaceId") UUID workspaceId
    );
}
