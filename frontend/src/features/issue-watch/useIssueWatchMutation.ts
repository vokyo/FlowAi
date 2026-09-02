import { useMutation, useQueryClient } from '@tanstack/react-query'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import { unwatchIssue, watchIssue } from '@/api/work-api'

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
