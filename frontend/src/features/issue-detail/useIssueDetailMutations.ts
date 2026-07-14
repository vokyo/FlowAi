import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createIssueComment, updateIssue, type UpdateIssueRequest } from '@/work/work-api'

export function useIssueDetailMutations(workspaceId: string | null) {
  const queryClient = useQueryClient()

  const invalidateBoard = (projectId: string) => {
    queryClient.removeQueries({ queryKey: ['project-board-column', workspaceId, projectId] })
    return queryClient.invalidateQueries({ queryKey: ['project-board', workspaceId, projectId] })
  }
  const invalidateAnalytics = (projectId: string) =>
    queryClient.invalidateQueries({ queryKey: ['project-analytics', workspaceId, projectId] })

  const createCommentMutation = useMutation({
    mutationFn: ({ issueId, body }: { issueId: string; projectId: string; body: string }) =>
      createIssueComment(issueId, { body }),
    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issue', workspaceId, variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-comments', workspaceId, variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-activities', workspaceId, variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, variables.projectId] }),
        invalidateBoard(variables.projectId),
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
        queryClient.invalidateQueries({ queryKey: ['issue', workspaceId, variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-activities', workspaceId, variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, issue.projectId] }),
        invalidateBoard(issue.projectId),
        invalidateAnalytics(issue.projectId),
      ])
    },
  })

  return { createCommentMutation, updateIssueMutation }
}
