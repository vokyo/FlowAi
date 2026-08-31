package com.vokyo.backend.issue;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class IssueWatchQuery {
    private final IssueWatcherRepository issueWatcherRepository;


    IssueWatchQuery(IssueWatcherRepository issueWatcherRepository) {
        this.issueWatcherRepository = issueWatcherRepository;
    }

    boolean isWatched(UUID issueId, UUID watcherId) {
        return issueWatcherRepository.existsById(new IssueWatcherId(issueId, watcherId));
    }

    long countByIssueId(UUID issueId) {
        return issueWatcherRepository.countByIssueId(issueId);
    }
}
