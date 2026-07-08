package com.vokyo.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByWorkspace_IdOrderByCreatedAtAsc(UUID workspaceId);

    Optional<Project> findByIdAndWorkspace_Id(UUID id, UUID workspaceId);
}
