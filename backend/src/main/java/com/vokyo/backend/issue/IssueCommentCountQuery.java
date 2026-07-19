package com.vokyo.backend.issue;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class IssueCommentCountQuery {

    private final IssueCommentRepository issueCommentRepository;

    IssueCommentCountQuery(IssueCommentRepository issueCommentRepository) {
        this.issueCommentRepository = issueCommentRepository;
    }

    Map<UUID, Long> load(List<Issue> issues) {
        if (issues.isEmpty()) {
            return Map.of();
        }

        List<UUID> issueIds = issues.stream().map(Issue::getId).toList();
        return issueCommentRepository.countByIssueIds(issueIds)
                .stream()
                .collect(Collectors.toMap(
                        IssueCommentRepository.IssueCommentCount::getIssueId,
                        IssueCommentRepository.IssueCommentCount::getCommentCount,
                        Long::sum
                ));
    }

    long load(Issue issue) {
        return load(List.of(issue)).getOrDefault(issue.getId(), 0L);
    }
}
