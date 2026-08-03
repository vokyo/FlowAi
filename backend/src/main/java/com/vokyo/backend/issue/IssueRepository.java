package com.vokyo.backend.issue;

import com.vokyo.backend.project.WorkflowStateCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID>, JpaSpecificationExecutor<Issue> {

    Optional<Issue> findByIdAndWorkspace_Id(UUID id, UUID workspaceId);

    /**
     * Ranks issues the way an AI project summary wants to read them — overdue
     * first, then by priority, then most recently touched — so the caller can stop
     * at its context limit instead of loading a whole project into memory.
     * Associations are left lazy on purpose and resolved by
     * hibernate.default_batch_fetch_size, because a collection fetch join here
     * would push the limit into memory.
     */
    @Query("""
            select issue
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.archivedAt is null
            order by
              case when issue.dueDate is not null
                        and issue.dueDate < :today
                        and issue.workflowState.category <> :doneCategory
                   then 0 else 1 end asc,
              case when issue.priority = :urgentPriority then 0
                   when issue.priority = :highPriority then 1
                   else 2 end asc,
              issue.updatedAt desc,
              issue.id asc
            """)
    List<Issue> findRankedActiveForAiSummary(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("today") LocalDate today,
            @Param("doneCategory") WorkflowStateCategory doneCategory,
            @Param("urgentPriority") IssuePriority urgentPriority,
            @Param("highPriority") IssuePriority highPriority,
            Pageable pageable
    );

    @Query("""
            select count(issue) as totalActive,
                   coalesce(sum(case when issue.dueDate is not null
                                          and issue.dueDate < :today
                                          and issue.workflowState.category <> :doneCategory
                                     then 1 else 0 end), 0) as overdue,
                   coalesce(sum(case when issue.priority = :urgentPriority
                                          or issue.priority = :highPriority
                                     then 1 else 0 end), 0) as highPriority,
                   coalesce(sum(case when issue.assigneeUser is null
                                     then 1 else 0 end), 0) as unassigned
            from Issue issue
            where issue.workspace.id = :workspaceId
              and issue.project.id = :projectId
              and issue.archivedAt is null
            """)
    AiSummaryIssueStats summarizeActiveForAiSummary(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("today") LocalDate today,
            @Param("doneCategory") WorkflowStateCategory doneCategory,
            @Param("urgentPriority") IssuePriority urgentPriority,
            @Param("highPriority") IssuePriority highPriority
    );

    interface AiSummaryIssueStats {
        long getTotalActive();

        long getOverdue();

        long getHighPriority();

        long getUnassigned();
    }

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
