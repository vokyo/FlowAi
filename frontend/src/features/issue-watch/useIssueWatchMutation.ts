import { useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import type { CursorPage } from '@/api/pagination'
import { unwatchIssue, watchIssue, type IssueDetail, type IssueSummary } from '@/api/work-api'

type WatchVariables = {
  issueId: string
  projectId: string
  watched: boolean
}

type IssueListData = InfiniteData<CursorPage<IssueSummary>>

function markWatchedInList(data: IssueListData | undefined, issueId: string, watched: boolean) {
  return data && {
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.map((issue) => (issue.id === issueId ? { ...issue, watched } : issue)),
    })),
  }
}

export function useIssueWatchMutation(workspaceId: string | null) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ issueId, watched }: WatchVariables) =>
      watched ? unwatchIssue(issueId) : watchIssue(issueId),

    onMutate: async ({ issueId, projectId, watched }) => {
      const key = queryKeys.issue(workspaceId, issueId)
      const listKey = queryKeys.issues(workspaceId, projectId)
      await Promise.all([
        queryClient.cancelQueries({ queryKey: key }),
        queryClient.cancelQueries({ queryKey: listKey }),
      ])
      const previous = queryClient.getQueryData<IssueDetail>(key)
      const previousLists = queryClient.getQueriesData<IssueListData>({ queryKey: listKey })

      queryClient.setQueryData<IssueDetail>(key, (old) => old && {
        ...old,
        watched: !watched,
        watcherCount: (old.watcherCount ?? 0) + (watched ? -1 : 1),
      })
      queryClient.setQueriesData<IssueListData>({ queryKey: listKey }, (old) =>
        markWatchedInList(old, issueId, !watched),
      )

      return { previous, previousLists }
    },

    onError: (_error, { issueId }, context) => {
      queryClient.setQueryData(queryKeys.issue(workspaceId, issueId), context?.previous)
      context?.previousLists.forEach(([listKey, data]) => queryClient.setQueryData(listKey, data))
    },

    onSuccess: async (state, variables) => {
      queryClient.setQueryData<IssueDetail>(queryKeys.issue(workspaceId, variables.issueId), (old) => old && {
        ...old,
        watched: state.watched,
        watcherCount: state.watcherCount,
      })
      queryClient.setQueriesData<IssueListData>(
        { queryKey: queryKeys.issues(workspaceId, variables.projectId) },
        (old) => markWatchedInList(old, variables.issueId, state.watched),
      )

      await resetProjectBoard(queryClient, workspaceId, variables.projectId)
    },
  })
}
