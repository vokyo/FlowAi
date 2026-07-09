package com.vokyo.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectWorkflowStateRepository extends JpaRepository<ProjectWorkflowState, UUID> {

    List<ProjectWorkflowState> findByWorkspace_IdAndProject_IdOrderByPositionAscNameAsc(UUID workspaceId, UUID projectId);

    Optional<ProjectWorkflowState> findByWorkspace_IdAndProject_IdAndId(UUID workspaceId, UUID projectId, UUID id);

    Optional<ProjectWorkflowState> findFirstByWorkspace_IdAndProject_IdAndCategoryOrderByPositionAscNameAsc(
            UUID workspaceId,
            UUID projectId,
            WorkflowStateCategory category
    );

    boolean existsByWorkspace_IdAndProject_IdAndNameIgnoreCase(UUID workspaceId, UUID projectId, String name);

    boolean existsByWorkspace_IdAndProject_IdAndNameIgnoreCaseAndIdNot(
            UUID workspaceId,
            UUID projectId,
            String name,
            UUID id
    );
}
