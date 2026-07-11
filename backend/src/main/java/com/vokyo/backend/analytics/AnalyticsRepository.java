package com.vokyo.backend.analytics;

import com.vokyo.backend.issue.Issue;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AnalyticsRepository extends Repository<Issue, UUID> {

    @Query(value = """
            select count(*) filter (where issue.archived_at is null) as "totalIssues",
                   count(*) filter (
                       where issue.archived_at is null
                         and workflow_state.category = 'DONE'
                   ) as "completedIssues",
                   count(*) filter (where issue.archived_at is not null) as "archivedIssues"
            from issues issue
            join project_workflow_states workflow_state
              on workflow_state.id = issue.workflow_state_id
            where issue.workspace_id = :workspaceId
              and issue.project_id = :projectId
            """, nativeQuery = true)
    SummaryProjection summarize(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId
    );

    @Query(value = """
            select workflow_state.category as "category",
                   count(*) as "issueCount"
            from issues issue
            join project_workflow_states workflow_state
              on workflow_state.id = issue.workflow_state_id
            where issue.workspace_id = :workspaceId
              and issue.project_id = :projectId
              and issue.archived_at is null
            group by workflow_state.category
            """, nativeQuery = true)
    List<StatusCountProjection> countByStatusCategory(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId
    );

    @Query(value = """
            select issue.assignee_user_id as "userId",
                   coalesce(assignee.display_name, 'Unassigned') as "displayName",
                   assignee.email as "email",
                   count(*) as "issueCount"
            from issues issue
            left join users assignee
              on assignee.id = issue.assignee_user_id
            where issue.workspace_id = :workspaceId
              and issue.project_id = :projectId
              and issue.archived_at is null
            group by issue.assignee_user_id, assignee.display_name, assignee.email
            order by count(*) desc,
                     coalesce(assignee.display_name, 'Unassigned') asc
            """, nativeQuery = true)
    List<AssigneeCountProjection> countByAssignee(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId
    );

    @Query(value = """
            select (issue.completed_at at time zone 'UTC')::date as "completionDate",
                   count(*) as "issueCount"
            from issues issue
            join project_workflow_states workflow_state
              on workflow_state.id = issue.workflow_state_id
            where issue.workspace_id = :workspaceId
              and issue.project_id = :projectId
              and issue.archived_at is null
              and workflow_state.category = 'DONE'
              and issue.completed_at >= :startInclusive
              and issue.completed_at < :endExclusive
            group by (issue.completed_at at time zone 'UTC')::date
            order by (issue.completed_at at time zone 'UTC')::date asc
            """, nativeQuery = true)
    List<CompletionTrendProjection> countCompletionsByUtcDate(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive
    );

    interface SummaryProjection {
        long getTotalIssues();

        long getCompletedIssues();

        long getArchivedIssues();
    }

    interface StatusCountProjection {
        String getCategory();

        long getIssueCount();
    }

    interface AssigneeCountProjection {
        UUID getUserId();

        String getDisplayName();

        String getEmail();

        long getIssueCount();
    }

    interface CompletionTrendProjection {
        LocalDate getCompletionDate();

        long getIssueCount();
    }
}
