import { useMemo } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { compareCreatedAtAscending, mergeCursorPageItems } from '@/api/pagination'
import { queryKeys } from '@/lib/query-keys'
import { getIssue, listIssueActivities, listIssueComments } from '@/api/work-api'

export function useIssueDetailQueries({
  workspaceId,
  issueId,
  enabled,
}: {
  workspaceId: string | null
  issueId: string | undefined
  enabled: boolean
}) {
  const issueQuery = useQuery({
    queryKey: queryKeys.issue(workspaceId, issueId),
    queryFn: () => getIssue(issueId ?? ''),
    enabled,
    retry: false,
  })
  const commentsQuery = useInfiniteQuery({
    queryKey: queryKeys.issueComments(workspaceId, issueId),
    queryFn: ({ pageParam }) => listIssueComments(issueId ?? '', pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled,
    retry: false,
  })
  const activitiesQuery = useInfiniteQuery({
    queryKey: queryKeys.issueActivities(workspaceId, issueId),
    queryFn: ({ pageParam }) => listIssueActivities(issueId ?? '', pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled,
    retry: false,
  })
  const comments = useMemo(
    () => mergeCursorPageItems(commentsQuery.data?.pages).sort(compareCreatedAtAscending),
    [commentsQuery.data?.pages],
  )
  const activities = useMemo(
    () => mergeCursorPageItems(activitiesQuery.data?.pages).sort(compareCreatedAtAscending),
    [activitiesQuery.data?.pages],
  )
  return { issueQuery, commentsQuery, activitiesQuery, comments, activities }
}
