import { useMutation, useQueryClient } from '@tanstack/react-query'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import { unwatchIssue, watchIssue, type IssueDetail } from '@/api/work-api'

type WatchVariables = {
  issueId: string
  projectId: string
  watched: boolean
}

export function useIssueWatchMutation(workspaceId: string | null) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ issueId, watched }: WatchVariables) =>
      watched ? unwatchIssue(issueId) : watchIssue(issueId),

    onMutate: async ({ issueId, watched }) => {
      const key = queryKeys.issue(workspaceId, issueId)
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<IssueDetail>(key)

      queryClient.setQueryData<IssueDetail>(key, (old) => old && {
        ...old,
        watched: !watched,
        watcherCount: (old.watcherCount ?? 0) + (watched ? -1 : 1),
      })

      return { previous }
    },

    onError: (_error, { issueId }, context) => {
      queryClient.setQueryData(queryKeys.issue(workspaceId, issueId), context?.previous)
    },

    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: queryKeys.issue(workspaceId, variables.issueId),
        }),
        queryClient.invalidateQueries({
          queryKey: queryKeys.issues(workspaceId, variables.projectId),
        }),
        resetProjectBoard(queryClient, workspaceId, variables.projectId),
      ])
    },
  })
}
