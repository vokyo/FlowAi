import { useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { queryKeys } from '@/lib/query-keys'
import type { CursorPage } from '@/api/pagination'
import {
  unwatchIssue,
  watchIssue,
  type IssueDetail,
  type IssueSummary,
  type ProjectBoard,
} from '@/api/work-api'

type WatchVariables = {
  issueId: string
  projectId: string
  watched: boolean
}

type IssueListData = InfiniteData<CursorPage<IssueSummary>>

function markWatched(issues: IssueSummary[], issueId: string, watched: boolean) {
  return issues.map((issue) => (issue.id === issueId ? { ...issue, watched } : issue))
}

function markWatchedInList(data: IssueListData | undefined, issueId: string, watched: boolean) {
  return data && {
    ...data,
    pages: data.pages.map((page) => ({ ...page, items: markWatched(page.items, issueId, watched) })),
  }
}

function markWatchedOnBoard(board: ProjectBoard | undefined, issueId: string, watched: boolean) {
  return board && {
    ...board,
    columns: board.columns.map((column) => ({ ...column, issues: markWatched(column.issues, issueId, watched) })),
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
      const boardKey = queryKeys.board(workspaceId, projectId)
      await Promise.all([
        queryClient.cancelQueries({ queryKey: key }),
        queryClient.cancelQueries({ queryKey: listKey }),
        queryClient.cancelQueries({ queryKey: boardKey }),
      ])
      const previous = queryClient.getQueryData<IssueDetail>(key)
      const previousLists = queryClient.getQueriesData<IssueListData>({ queryKey: listKey })
      const previousBoard = queryClient.getQueryData<ProjectBoard>(boardKey)

      queryClient.setQueryData<IssueDetail>(key, (old) => old && {
        ...old,
        watched: !watched,
        watcherCount: (old.watcherCount ?? 0) + (watched ? -1 : 1),
      })
      queryClient.setQueriesData<IssueListData>({ queryKey: listKey }, (old) =>
        markWatchedInList(old, issueId, !watched),
      )
      queryClient.setQueryData<ProjectBoard>(boardKey, (old) => markWatchedOnBoard(old, issueId, !watched))

      return { previous, previousLists, previousBoard }
    },

    onError: (_error, { issueId, projectId }, context) => {
      queryClient.setQueryData(queryKeys.issue(workspaceId, issueId), context?.previous)
      context?.previousLists.forEach(([listKey, data]) => queryClient.setQueryData(listKey, data))
      queryClient.setQueryData(queryKeys.board(workspaceId, projectId), context?.previousBoard)
    },

    onSuccess: (state, variables) => {
      queryClient.setQueryData<IssueDetail>(queryKeys.issue(workspaceId, variables.issueId), (old) => old && {
        ...old,
        watched: state.watched,
        watcherCount: state.watcherCount,
      })
      queryClient.setQueriesData<IssueListData>(
        { queryKey: queryKeys.issues(workspaceId, variables.projectId) },
        (old) => markWatchedInList(old, variables.issueId, state.watched),
      )
      queryClient.setQueryData<ProjectBoard>(queryKeys.board(workspaceId, variables.projectId), (old) =>
        markWatchedOnBoard(old, variables.issueId, state.watched),
      )
    },
  })
}
