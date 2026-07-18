package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID>, JpaSpecificationExecutor<Issue> {

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
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
            order by issue.boardPosition asc, issue.id asc
            """)
    List<Issue> findFirstActiveIssuesInWorkflowState(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId,
            Pageable pageable
    );

    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
              and (issue.boardPosition > :boardPosition
                   or (issue.boardPosition = :boardPosition and issue.id > :id))
            order by issue.boardPosition asc, issue.id asc
            """)
    List<Issue> findActiveIssuesInWorkflowStateAfter(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId,
            @Param("boardPosition") long boardPosition,
            @Param("id") UUID id,
            Pageable pageable
    );

    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
              and issue.id <> :excludedIssueId
            order by issue.boardPosition asc, issue.id asc
            """)
    List<Issue> findFirstActiveIssueInWorkflowStateExcludingIssue(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId,
            @Param("excludedIssueId") UUID excludedIssueId,
            Pageable pageable
    );

    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
              and issue.id <> :excludedIssueId
              and (issue.boardPosition > :boardPosition
                   or (issue.boardPosition = :boardPosition and issue.id > :id))
            order by issue.boardPosition asc, issue.id asc
            """)
    List<Issue> findFirstActiveIssueAfterExcludingIssue(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId,
            @Param("boardPosition") long boardPosition,
            @Param("id") UUID id,
            @Param("excludedIssueId") UUID excludedIssueId,
            Pageable pageable
    );

    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
              and issue.archivedAt is null
            order by issue.boardPosition asc, issue.id asc
            """)
    List<Issue> findAllActiveIssuesInWorkflowState(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId
    );

    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.workflowState.id = :workflowStateId
            order by issue.id asc
            """)
    List<Issue> findWorkflowStateBatch(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("workflowStateId") UUID workflowStateId,
            Pageable pageable
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
