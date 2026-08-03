import { useMemo } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { mergeCursorPageItems } from '@/api/pagination'
import { queryKeys } from '@/lib/query-keys'
import { listIssues, type ListIssuesFilters } from '@/api/work-api'

export function useIssueListQueries({
  workspaceId,
  projectId,
  filters,
  filterKey,
  enabled,
}: {
  workspaceId: string | null
  projectId: string | null
  filters: ListIssuesFilters
  filterKey: readonly unknown[]
  enabled: boolean
}) {
  const issuesQuery = useInfiniteQuery({
    queryKey: queryKeys.issueList(workspaceId, projectId, filterKey),
    queryFn: ({ pageParam }) => listIssues(projectId ?? '', filters, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled,
    retry: false,
  })
  const issues = useMemo(
    () => mergeCursorPageItems(issuesQuery.data?.pages),
    [issuesQuery.data?.pages],
  )
  return { issuesQuery, issues }
}
