package com.vokyo.backend.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {

    List<ActivityEvent> findByIssue_IdAndWorkspace_IdOrderByCreatedAtAsc(UUID issueId, UUID workspaceId);
}
