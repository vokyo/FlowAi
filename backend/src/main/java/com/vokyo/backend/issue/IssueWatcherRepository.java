package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IssueWatcherRepository extends JpaRepository<IssueWatcher, IssueWatcherId> {
    long countByIssueId(UUID issueId);

    List<IssueWatcher> findByUserIdAndIssueIdIn(UUID userId, Collection<UUID> issueIds);

    void deleteByProjectIdAndUserId(UUID projectId, UUID userId);
}
