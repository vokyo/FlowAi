package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IssueCommentRepository extends JpaRepository<IssueComment, UUID> {

    @Query("""
            select comment
            from IssueComment comment
            where comment.workspace.id = :workspaceId
              and comment.project.id = :projectId
              and comment.issue.id = :issueId
            order by comment.createdAt desc, comment.id desc
            """)
    List<IssueComment> findFirstPage(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("issueId") UUID issueId,
            Pageable pageable
    );

    @Query("""
            select comment
            from IssueComment comment
            where comment.workspace.id = :workspaceId
              and comment.project.id = :projectId
              and comment.issue.id = :issueId
              and (comment.createdAt < :createdAt
                   or (comment.createdAt = :createdAt and comment.id < :id))
            order by comment.createdAt desc, comment.id desc
            """)
    List<IssueComment> findPageAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("issueId") UUID issueId,
            @Param("createdAt") Instant createdAt,
            @Param("id") UUID id,
            Pageable pageable
    );

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
