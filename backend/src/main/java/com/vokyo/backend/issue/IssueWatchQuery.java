package com.vokyo.backend.issue;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class IssueWatchQuery {

    private final IssueWatcherRepository issueWatcherRepository;

    IssueWatchQuery(IssueWatcherRepository issueWatcherRepository) {
        this.issueWatcherRepository = issueWatcherRepository;
    }

    boolean isWatched(UUID issueId, UUID userId) {
        return issueWatcherRepository.existsById(new IssueWatcherId(issueId, userId));
    }

    long countByIssueId(UUID issueId) {
        return issueWatcherRepository.countByIssueId(issueId);
    }

    Set<UUID> loadWatchedIssueIds(List<Issue> issues, UUID userId) {
        if (issues.isEmpty()) {
            return Set.of();
        }

        List<UUID> issueIds = issues.stream().map(Issue::getId).toList();
        return issueWatcherRepository.findByUserIdAndIssueIdIn(userId, issueIds)
                .stream()
                .map(IssueWatcher::getIssueId)
                .collect(Collectors.toSet());
    }
}
