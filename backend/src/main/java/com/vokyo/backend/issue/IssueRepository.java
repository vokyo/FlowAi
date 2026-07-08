package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID>, JpaSpecificationExecutor<Issue> {

    List<Issue> findByWorkspace_IdAndProject_IdOrderByCreatedAtDesc(UUID workspaceId, UUID projectId);

    Optional<Issue> findByIdAndWorkspace_Id(UUID id, UUID workspaceId);
}
