import { useMutation, useQueryClient } from '@tanstack/react-query'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import {
  createIssue,
  createProjectLabel,
  type IssueSummary,
} from '@/api/work-api'

export function useIssueMutations({
  workspaceId,
  onIssueCreated,
}: {
  workspaceId: string | null
  onIssueCreated: (issue: IssueSummary) => void
}) {
  const queryClient = useQueryClient()
  const invalidateAnalytics = (projectId: string) =>
    queryClient.invalidateQueries({ queryKey: queryKeys.analytics(workspaceId, projectId) })

  const createIssueMutation = useMutation({
    mutationFn: createIssue,
    onSuccess: async (issue) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, issue.projectId) }),
        resetProjectBoard(queryClient, workspaceId, issue.projectId),
        invalidateAnalytics(issue.projectId),
      ])
      onIssueCreated(issue)
    },
  })

  const createProjectLabelMutation = useMutation({
    mutationFn: ({ projectId, name, color }: { projectId: string; name: string; color: string }) =>
      createProjectLabel(projectId, { name, color }),
    onSuccess: async (label) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.projectLabels(label.projectId) })
    },
  })

  return { createIssueMutation, createProjectLabelMutation }
}
