package com.vokyo.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectLabelRepository extends JpaRepository<ProjectLabel, UUID> {

    List<ProjectLabel> findByWorkspace_IdAndProject_IdOrderByNameAsc(UUID workspaceId, UUID projectId);

    boolean existsByWorkspace_IdAndProject_IdAndNameIgnoreCase(UUID workspaceId, UUID projectId, String name);

    boolean existsByWorkspace_IdAndProject_IdAndNameIgnoreCaseAndIdNot(
            UUID workspaceId, UUID projectId, String name, UUID id
    );

    java.util.Optional<ProjectLabel> findByWorkspace_IdAndProject_IdAndId(
            UUID workspaceId, UUID projectId, UUID id
    );

    List<ProjectLabel> findByWorkspace_IdAndProject_IdAndIdIn(
            UUID workspaceId,
            UUID projectId,
            Collection<UUID> ids
    );
}
