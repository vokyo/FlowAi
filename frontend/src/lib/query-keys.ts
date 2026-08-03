import type { QueryClient } from '@tanstack/react-query'

/**
 * Query keys for the project/issue domain, plus the cache operations that have to
 * touch more than one of them at once.
 *
 * These used to be array literals written out at each call site. React Query
 * matches keys structurally, so a read side and a write side that disagree by one
 * element fail silently — and one of the five board invalidations had already
 * drifted, which let a stale column cursor skip a page of issues. Keys that more
 * than one file depends on belong here.
 */

type Id = string | null | undefined

export const queryKeys = {
  board: (workspaceId: Id, projectId: Id) => ['project-board', workspaceId, projectId] as const,

  /** Prefix over every column of a board; append a workflowStateId for one column. */
  boardColumns: (workspaceId: Id, projectId: Id) =>
    ['project-board-column', workspaceId, projectId] as const,
  boardColumn: (workspaceId: Id, projectId: Id, workflowStateId: string) =>
    ['project-board-column', workspaceId, projectId, workflowStateId] as const,

  /** Prefix over every filter combination; append a filterKey for one list. */
  issues: (workspaceId: Id, projectId: Id) => ['issues', workspaceId, projectId] as const,
  issueList: (workspaceId: Id, projectId: Id, filterKey: readonly unknown[]) =>
    ['issues', workspaceId, projectId, ...filterKey] as const,

  /** Prefix over every issue detail, used when a change can affect issues we cannot name. */
  allIssueDetails: () => ['issue'] as const,
  issue: (workspaceId: Id, issueId: Id) => ['issue', workspaceId, issueId] as const,
  issueComments: (workspaceId: Id, issueId: Id) => ['issue-comments', workspaceId, issueId] as const,
  issueActivities: (workspaceId: Id, issueId: Id) =>
    ['issue-activities', workspaceId, issueId] as const,

  /** Prefix over every range; append a rangeDays for one chart. */
  analytics: (workspaceId: Id, projectId: Id) =>
    ['project-analytics', workspaceId, projectId] as const,
  analyticsRange: (workspaceId: Id, projectId: Id, rangeDays: number) =>
    ['project-analytics', workspaceId, projectId, rangeDays] as const,

  // Project metadata is cached per project, without a workspace segment.
  projectWorkflowStates: (projectId: Id) => ['project-workflow-states', projectId] as const,
  projectLabels: (projectId: Id) => ['project-labels', projectId] as const,
}

/**
 * Invalidates a board together with its per-column pagination.
 *
 * The board query holds the issues; each column's infinite query holds the cursor
 * that says where the next page starts. Refetching the board alone rewinds it to
 * page one while the column query keeps pointing past the pages that were just
 * dropped, so the next "Load more" fetches page N+1 and the issues in between
 * never come back. Always reset both.
 */
export function resetProjectBoard(
  queryClient: QueryClient,
  workspaceId: Id,
  projectId: Id,
) {
  queryClient.removeQueries({ queryKey: queryKeys.boardColumns(workspaceId, projectId) })
  return queryClient.invalidateQueries({ queryKey: queryKeys.board(workspaceId, projectId) })
}
