import type { IssuePriority, ListIssuesFilters } from '@/work/work-api'
import type { IssueWorkflowFilter } from '@/work/board-utils'

export function buildIssueListFilterState({
  searchQuery,
  workflowFilter,
  priorityFilter,
  labelFilter,
  assigneeFilter,
}: {
  searchQuery: string
  workflowFilter: IssueWorkflowFilter
  priorityFilter: IssuePriority | ''
  labelFilter: string
  assigneeFilter: string
}) {
  const normalizedSearchQuery = searchQuery.trim()
  const filters: ListIssuesFilters = {
    status: workflowFilter === 'ARCHIVED' ? 'ARCHIVED' : undefined,
    workflowStateId:
      workflowFilter === 'ACTIVE' || workflowFilter === 'ARCHIVED'
        ? undefined
        : workflowFilter,
    priority: priorityFilter || undefined,
    labelId: labelFilter || undefined,
    assigneeUserId: assigneeFilter || undefined,
    q: normalizedSearchQuery || undefined,
  }
  return {
    filters,
    normalizedSearchQuery,
    filterKey: [
      workflowFilter,
      priorityFilter,
      labelFilter,
      assigneeFilter,
      normalizedSearchQuery,
    ] as const,
    hasFilters: Boolean(
      normalizedSearchQuery ||
      workflowFilter !== 'ACTIVE' ||
      priorityFilter ||
      labelFilter ||
      assigneeFilter
    ),
  }
}
