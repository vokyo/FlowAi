package com.vokyo.backend.issue;

import org.springframework.stereotype.Component;

import java.util.*;

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

    Set<UUID> loadWatchedIssueIds(List<Issue> issues, UUID userId) {
        if (issues == null || issues.isEmpty()) {
            return Set.of();
        }
        List<UUID> issueIds = new ArrayList<>();
        for (Issue issue : issues) {
            issueIds.add(issue.getId());
        }
        List<IssueWatcher> watchers = issueWatcherRepository.findByUserIdAndIssueIdIn(userId, issueIds);
        Set<UUID> watchedIds = new HashSet<>();
        for (IssueWatcher watcher : watchers) {
            watchedIds.add(watcher.getIssueId());
        }
        return watchedIds;

    }
}
