import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createIssue,
  createProjectLabel,
  type IssueSummary,
} from '@/work/work-api'

export function useIssueMutations({
  workspaceId,
  onIssueCreated,
}: {
  workspaceId: string | null
  onIssueCreated: (issue: IssueSummary) => void
}) {
  const queryClient = useQueryClient()
  const invalidateBoard = (projectId: string) => {
    queryClient.removeQueries({ queryKey: ['project-board-column', workspaceId, projectId] })
    return queryClient.invalidateQueries({ queryKey: ['project-board', workspaceId, projectId] })
  }
  const invalidateAnalytics = (projectId: string) =>
    queryClient.invalidateQueries({ queryKey: ['project-analytics', workspaceId, projectId] })

  const createIssueMutation = useMutation({
    mutationFn: createIssue,
    onSuccess: async (issue) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, issue.projectId] }),
        invalidateBoard(issue.projectId),
        invalidateAnalytics(issue.projectId),
      ])
      onIssueCreated(issue)
    },
  })

  const createProjectLabelMutation = useMutation({
    mutationFn: ({ projectId, name, color }: { projectId: string; name: string; color: string }) =>
      createProjectLabel(projectId, { name, color }),
    onSuccess: async (label) => {
      await queryClient.invalidateQueries({ queryKey: ['project-labels', label.projectId] })
    },
  })

  return { createIssueMutation, createProjectLabelMutation }
}
