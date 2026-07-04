package com.vokyo.backend.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    boolean existsBySlug(String slug);

    Optional<Workspace> findFirstByOwner_IdOrderByCreatedAtAsc(UUID ownerId);
}