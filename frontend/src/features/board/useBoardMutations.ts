import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createIssue,
  createProjectWorkflowState,
  reorderIssues,
  reorderProjectWorkflowStates,
  updateProjectWorkflowState,
  type ProjectBoard,
  type WorkflowStateCategory,
} from '@/work/work-api'
import {
  applyBoardReorderResult,
  appendIssueToBoard,
} from '@/work/board-utils'
import type {
  QuickCreateIssueMutationVariables,
  ReorderIssueMutationVariables,
  UpdateProjectWorkflowStateFormValues,
} from '@/features/project-shell/project-model'

export function useBoardMutations(workspaceId: string | null) {
  const queryClient = useQueryClient()
  const boardKey = (projectId: string) => ['project-board', workspaceId, projectId] as const
  const invalidateAnalytics = (projectId: string) =>
    queryClient.invalidateQueries({ queryKey: ['project-analytics', workspaceId, projectId] })
  const resetAndInvalidateBoard = (projectId: string) => {
    queryClient.removeQueries({ queryKey: ['project-board-column', workspaceId, projectId] })
    return queryClient.invalidateQueries({ queryKey: boardKey(projectId) })
  }

  const quickCreateIssueMutation = useMutation({
    mutationFn: ({ projectId, title, workflowStateId, assigneeUserId }: QuickCreateIssueMutationVariables) =>
      createIssue({ projectId, title, workflowStateId, assigneeUserId }),
    onSuccess: (issue) => {
      queryClient.setQueryData<ProjectBoard>(boardKey(issue.projectId), (board) =>
        appendIssueToBoard(board, issue),
      )
      void Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, issue.projectId] }),
        invalidateAnalytics(issue.projectId),
      ])
    },
  })

  const reorderIssueMutation = useMutation({
    mutationFn: ({ request }: ReorderIssueMutationVariables) => reorderIssues(request),
    onMutate: async (variables) => {
      const queryKey = boardKey(variables.projectId)
      await queryClient.cancelQueries({ queryKey })
      const previousBoard = queryClient.getQueryData<ProjectBoard>(queryKey)
      queryClient.setQueryData(queryKey, variables.optimisticBoard)
      return { previousBoard, queryKey }
    },
    onError: (_, __, context) => {
      if (context?.previousBoard) queryClient.setQueryData(context.queryKey, context.previousBoard)
    },
    onSuccess: (result, variables) => {
      queryClient.setQueryData<ProjectBoard>(boardKey(variables.projectId), (board) =>
        applyBoardReorderResult(board, result),
      )
      if (result.rebalanced) {
        queryClient.removeQueries({
          queryKey: ['project-board-column', workspaceId, variables.projectId],
        })
        void queryClient.invalidateQueries({ queryKey: boardKey(variables.projectId) })
      }
    },
    onSettled: async (_, __, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, variables.projectId] }),
        queryClient.invalidateQueries({ queryKey: ['issue', workspaceId, variables.request.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-activities', workspaceId, variables.request.issueId] }),
        invalidateAnalytics(variables.projectId),
      ])
    },
  })

  const createWorkflowStateMutation = useMutation({
    mutationFn: ({ projectId, name, category }: { projectId: string; name: string; category: WorkflowStateCategory }) =>
      createProjectWorkflowState(projectId, { name, category }),
    onSuccess: async (workflowState) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['project-workflow-states', workflowState.projectId] }),
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, workflowState.projectId] }),
        resetAndInvalidateBoard(workflowState.projectId),
      ])
    },
  })

  const updateWorkflowStateMutation = useMutation({
    mutationFn: ({ projectId, workflowStateId, values }: { projectId: string; workflowStateId: string; values: UpdateProjectWorkflowStateFormValues }) =>
      updateProjectWorkflowState(projectId, workflowStateId, values),
    onSuccess: async (workflowState) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['project-workflow-states', workflowState.projectId] }),
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, workflowState.projectId] }),
        resetAndInvalidateBoard(workflowState.projectId),
        queryClient.invalidateQueries({ queryKey: ['issue'] }),
        invalidateAnalytics(workflowState.projectId),
      ])
    },
  })

  const reorderWorkflowStatesMutation = useMutation({
    mutationFn: ({ projectId, workflowStateIds }: { projectId: string; workflowStateIds: string[] }) =>
      reorderProjectWorkflowStates(projectId, { workflowStateIds }),
    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['project-workflow-states', variables.projectId] }),
        queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, variables.projectId] }),
        resetAndInvalidateBoard(variables.projectId),
        queryClient.invalidateQueries({ queryKey: ['issue'] }),
      ])
    },
  })

  return {
    quickCreateIssueMutation,
    reorderIssueMutation,
    createWorkflowStateMutation,
    updateWorkflowStateMutation,
    reorderWorkflowStatesMutation,
  }
}
