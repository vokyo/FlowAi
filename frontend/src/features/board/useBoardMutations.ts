import { useMutation, useQueryClient } from '@tanstack/react-query'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import {
  createIssue,
  createProjectWorkflowState,
  reorderIssues,
  reorderProjectWorkflowStates,
  updateProjectWorkflowState,
  type ProjectBoard,
  type WorkflowStateCategory,
} from '@/api/work-api'
import {
  applyBoardReorderResult,
  appendIssueToBoard,
} from '@/domain/board-utils'
import type {
  QuickCreateIssueMutationVariables,
  ReorderIssueMutationVariables,
  UpdateProjectWorkflowStateFormValues,
} from '@/domain/project-model'

export function useBoardMutations(workspaceId: string | null) {
  const queryClient = useQueryClient()
  const invalidateAnalytics = (projectId: string) =>
    queryClient.invalidateQueries({ queryKey: queryKeys.analytics(workspaceId, projectId) })
  const resetBoard = (projectId: string) =>
    resetProjectBoard(queryClient, workspaceId, projectId)

  const quickCreateIssueMutation = useMutation({
    mutationFn: ({ projectId, title, workflowStateId, assigneeUserId }: QuickCreateIssueMutationVariables) =>
      createIssue({ projectId, title, workflowStateId, assigneeUserId }),
    onSuccess: (issue) => {
      queryClient.setQueryData<ProjectBoard>(queryKeys.board(workspaceId, issue.projectId), (board) =>
        appendIssueToBoard(board, issue),
      )
      void Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, issue.projectId) }),
        invalidateAnalytics(issue.projectId),
      ])
    },
  })

  const reorderIssueMutation = useMutation({
    mutationFn: ({ request }: ReorderIssueMutationVariables) => reorderIssues(request),
    onMutate: async (variables) => {
      const queryKey = queryKeys.board(workspaceId, variables.projectId)
      await queryClient.cancelQueries({ queryKey })
      const previousBoard = queryClient.getQueryData<ProjectBoard>(queryKey)
      queryClient.setQueryData(queryKey, variables.optimisticBoard)
      return { previousBoard, queryKey }
    },
    onError: (_, __, context) => {
      if (context?.previousBoard) queryClient.setQueryData(context.queryKey, context.previousBoard)
    },
    onSuccess: (result, variables) => {
      queryClient.setQueryData<ProjectBoard>(queryKeys.board(workspaceId, variables.projectId), (board) =>
        applyBoardReorderResult(board, result),
      )
      if (result.rebalanced) {
        void resetBoard(variables.projectId)
      }
    },
    onSettled: async (_, __, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, variables.projectId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issue(workspaceId, variables.request.issueId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issueActivities(workspaceId, variables.request.issueId) }),
        invalidateAnalytics(variables.projectId),
      ])
    },
  })

  const createWorkflowStateMutation = useMutation({
    mutationFn: ({ projectId, name, category }: { projectId: string; name: string; category: WorkflowStateCategory }) =>
      createProjectWorkflowState(projectId, { name, category }),
    onSuccess: async (workflowState) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.projectWorkflowStates(workflowState.projectId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, workflowState.projectId) }),
        resetBoard(workflowState.projectId),
      ])
    },
  })

  const updateWorkflowStateMutation = useMutation({
    mutationFn: ({ projectId, workflowStateId, values }: { projectId: string; workflowStateId: string; values: UpdateProjectWorkflowStateFormValues }) =>
      updateProjectWorkflowState(projectId, workflowStateId, values),
    onSuccess: async (workflowState) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.projectWorkflowStates(workflowState.projectId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, workflowState.projectId) }),
        resetBoard(workflowState.projectId),
        queryClient.invalidateQueries({ queryKey: queryKeys.allIssueDetails() }),
        invalidateAnalytics(workflowState.projectId),
      ])
    },
  })

  const reorderWorkflowStatesMutation = useMutation({
    mutationFn: ({ projectId, workflowStateIds }: { projectId: string; workflowStateIds: string[] }) =>
      reorderProjectWorkflowStates(projectId, { workflowStateIds }),
    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.projectWorkflowStates(variables.projectId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, variables.projectId) }),
        resetBoard(variables.projectId),
        queryClient.invalidateQueries({ queryKey: queryKeys.allIssueDetails() }),
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
