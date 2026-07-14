package com.vokyo.backend.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {

    @Query("""
            select event
            from ActivityEvent event
            where event.workspace.id = :workspaceId
              and event.project.id = :projectId
              and event.issue.id = :issueId
            order by event.createdAt desc, event.id desc
            """)
    List<ActivityEvent> findFirstPage(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("issueId") UUID issueId,
            Pageable pageable
    );

    @Query("""
            select event
            from ActivityEvent event
            where event.workspace.id = :workspaceId
              and event.project.id = :projectId
              and event.issue.id = :issueId
              and (event.createdAt < :createdAt
                   or (event.createdAt = :createdAt and event.id < :id))
            order by event.createdAt desc, event.id desc
            """)
    List<ActivityEvent> findPageAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("issueId") UUID issueId,
            @Param("createdAt") Instant createdAt,
            @Param("id") UUID id,
            Pageable pageable
    );
}
