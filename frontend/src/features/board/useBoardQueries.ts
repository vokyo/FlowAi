import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { CursorPage } from '@/api/pagination'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import { queryKeys } from '@/lib/query-keys'
import {
  getProjectBoard,
  listProjectWorkflowStates,
  type ProjectWorkflowState,
  type IssueSummary,
  type ProjectBoard,
} from '@/api/work-api'
import { appendBoardColumnPage } from '@/domain/board-utils'

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
    queryKey: queryKeys.projectWorkflowStates(projectId),
    queryFn: () => listProjectWorkflowStates(projectId ?? ''),
    enabled: metadataEnabled,
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
    retry: false,
  })
  const boardQuery = useQuery({
    queryKey: queryKeys.board(workspaceId, projectId),
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
      queryKeys.board(workspaceId, projectId),
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
