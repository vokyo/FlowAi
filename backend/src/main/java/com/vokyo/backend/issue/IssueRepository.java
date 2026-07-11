package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID>, JpaSpecificationExecutor<Issue> {

    List<Issue> findByWorkspace_IdAndProject_IdOrderByCreatedAtDesc(UUID workspaceId, UUID projectId);

    Optional<Issue> findByIdAndWorkspace_Id(UUID id, UUID workspaceId);

    @Query("""
            select issue.project.id
            from Issue issue
            where issue.id = :issueId
              and issue.workspace.id = :workspaceId
            """)
    Optional<UUID> findProjectIdByIdAndWorkspaceId(
            @Param("issueId") UUID issueId,
            @Param("workspaceId") UUID workspaceId
    );

    @Query("""
            select coalesce(max(issue.boardPosition), 0)
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
            """)
    long findMaxActiveBoardPosition(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId
    );

    @Query("""
            select coalesce(max(issue.boardPosition), 0)
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
              and issue.id <> :excludedIssueId
            """)
    long findMaxActiveBoardPositionExcludingIssue(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId,
            @Param("excludedIssueId") UUID excludedIssueId
    );

    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.archivedAt is null
            order by issue.workflowState.position asc,
                     issue.boardPosition asc,
                     issue.createdAt desc,
                     issue.id asc
            """)
    List<Issue> findActiveBoardIssues(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId
    );

    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
            order by issue.boardPosition asc,
                     issue.createdAt desc,
                     issue.id asc
            """)
    List<Issue> findActiveIssuesInWorkflowState(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId
    );

    List<Issue> findByWorkspace_IdAndProject_IdAndIdIn(
            UUID workspaceId,
            UUID projectId,
            Collection<UUID> issueIds
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update Issue issue
            set issue.completedAt = :completedAt
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
            """)
    int markActiveWorkflowStateIssuesCompleted(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId,
            @Param("completedAt") Instant completedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update Issue issue
            set issue.completedAt = null
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
            """)
    int clearWorkflowStateIssueCompletion(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId
    );
}
