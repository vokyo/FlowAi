package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface IssueWatcherRepository extends JpaRepository<IssueWatcher, IssueWatcherId> {
    long countByIssueId(UUID issueId);
}
