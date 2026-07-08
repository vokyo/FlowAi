package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IssueCommentRepository extends JpaRepository<IssueComment, UUID> {

    List<IssueComment> findByIssue_IdOrderByCreatedAtAsc(UUID issueId);

    @Query("""
            select c.issue.id as issueId, count(c.id) as commentCount
            from IssueComment c
            where c.issue.id in :issueIds
            group by c.issue.id
            """)
    List<IssueCommentCount> countByIssueIds(@Param("issueIds") Collection<UUID> issueIds);

    interface IssueCommentCount {
        UUID getIssueId();

        long getCommentCount();
    }
}
