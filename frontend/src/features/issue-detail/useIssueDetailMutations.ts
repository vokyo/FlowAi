import { useMutation, useQueryClient } from '@tanstack/react-query'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import { createIssueComment, updateIssue, type UpdateIssueRequest } from '@/api/work-api'

export function useIssueDetailMutations(workspaceId: string | null) {
  const queryClient = useQueryClient()

  const resetBoard = (projectId: string) =>
    resetProjectBoard(queryClient, workspaceId, projectId)
  const invalidateAnalytics = (projectId: string) =>
    queryClient.invalidateQueries({ queryKey: queryKeys.analytics(workspaceId, projectId) })

  const createCommentMutation = useMutation({
    mutationFn: ({ issueId, body }: { issueId: string; projectId: string; body: string }) =>
      createIssueComment(issueId, { body }),
    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.issue(workspaceId, variables.issueId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issueComments(workspaceId, variables.issueId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issueActivities(workspaceId, variables.issueId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, variables.projectId) }),
        resetBoard(variables.projectId),
      ])
    },
  })

  const updateIssueMutation = useMutation({
    mutationFn: ({
      issueId,
      request,
    }: {
      issueId: string
      projectId: string
      request: UpdateIssueRequest
    }) => updateIssue(issueId, request),
    onSuccess: async (issue, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.issue(workspaceId, variables.issueId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issueActivities(workspaceId, variables.issueId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, issue.projectId) }),
        resetBoard(issue.projectId),
        invalidateAnalytics(issue.projectId),
      ])
    },
  })

  return { createCommentMutation, updateIssueMutation }
}
