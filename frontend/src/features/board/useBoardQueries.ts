import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { CursorPage } from '@/api/pagination'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import {
  getProjectBoard,
  listProjectWorkflowStates,
  type ProjectWorkflowState,
  type IssueSummary,
  type ProjectBoard,
} from '@/work/work-api'
import { appendBoardColumnPage } from '@/work/board-utils'

const EMPTY_WORKFLOW_STATES: ProjectWorkflowState[] = []

export function useBoardQueries({
  workspaceId,
  projectId,
  metadataEnabled,
  boardEnabled,
}: {
  workspaceId: string | null
  projectId: string | null
  metadataEnabled: boolean
  boardEnabled: boolean
}) {
  const queryClient = useQueryClient()
  const workflowStatesQuery = useQuery({
    queryKey: ['project-workflow-states', projectId],
    queryFn: () => listProjectWorkflowStates(projectId ?? ''),
    enabled: metadataEnabled,
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
    retry: false,
  })
  const boardQuery = useQuery({
    queryKey: ['project-board', workspaceId, projectId],
    queryFn: () => getProjectBoard(projectId ?? ''),
    enabled: boardEnabled,
    retry: false,
  })
  const mergeBoardColumnPage = (
    workflowStateId: string,
    page: CursorPage<IssueSummary>,
  ) => {
    if (!projectId) return
    queryClient.setQueryData<ProjectBoard>(
      ['project-board', workspaceId, projectId],
      (board) => appendBoardColumnPage(board, workflowStateId, page),
    )
  }

  return {
    workflowStatesQuery,
    workflowStates: workflowStatesQuery.data ?? EMPTY_WORKFLOW_STATES,
    boardQuery,
    board: boardQuery.data ?? null,
    mergeBoardColumnPage,
  }
}
