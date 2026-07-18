import { arrayMove } from '@dnd-kit/sortable'
import type { CursorPage } from '@/api/pagination'
import type {
  IssueStatus,
  IssueSummary,
  ProjectBoard,
  ProjectWorkflowState,
  ReorderIssuesResponse,
} from '@/work/work-api'

export type IssueGroup = {
  status: IssueStatus
  workflowState: ProjectWorkflowState | null
  label: string
  issues: IssueSummary[]
}

export type IssueWorkflowFilter = 'ACTIVE' | 'ARCHIVED' | string
export type BoardIssueView = 'ALL' | 'MINE' | 'UNASSIGNED'

export function kanbanColumnId(workflowStateId: string) {
  return `kanban-column-${workflowStateId}`
}

export function filterProjectBoard(
  board: ProjectBoard | null,
  view: BoardIssueView,
  currentUserId: string | null,
) {
  if (!board || view === 'ALL') {
    return board
  }

  return {
    ...board,
    columns: board.columns.map((column) => ({
      ...column,
      issues: column.issues.filter((issue) =>
        view === 'MINE'
          ? Boolean(currentUserId && issue.assignee?.id === currentUserId)
          : !issue.assignee,
      ),
    })),
  }
}

export function boardEmptyColumnLabel(view: BoardIssueView) {
  if (view === 'MINE') {
    return 'No issues assigned to you'
  }

  if (view === 'UNASSIGNED') {
    return 'No unassigned issues'
  }

  return 'No issues'
}

export function appendIssueToBoard(
  board: ProjectBoard | undefined,
  issue: IssueSummary,
) {
  if (!board || board.projectId !== issue.projectId) {
    return board
  }

  return {
    ...board,
    columns: board.columns.map((column) => {
      if (
        column.workflowState.id !== issue.workflowState.id ||
        column.issues.some((currentIssue) => currentIssue.id === issue.id)
      ) {
        return column
      }

      return {
        ...column,
        issues: [...column.issues, issue].sort(
          (left, right) => left.boardPosition - right.boardPosition,
        ),
      }
    }),
  }
}

export function appendBoardColumnPage(
  board: ProjectBoard | undefined,
  workflowStateId: string,
  page: CursorPage<IssueSummary>,
) {
  if (!board) {
    return board
  }

  return {
    ...board,
    columns: board.columns.map((column) => {
      if (column.workflowState.id !== workflowStateId) {
        return column
      }

      const issues = new Map(column.issues.map((issue) => [issue.id, issue]))
      for (const issue of page.items) {
        issues.set(issue.id, issue)
      }

      return {
        ...column,
        issues: Array.from(issues.values()),
        nextCursor: page.nextCursor,
      }
    }),
  }
}

export function findBoardIssue(board: ProjectBoard, issueId: string) {
  for (const column of board.columns) {
    const issue = column.issues.find((candidate) => candidate.id === issueId)
    if (issue) {
      return issue
    }
  }

  return null
}

export function buildOptimisticBoard(
  board: ProjectBoard,
  issueId: string,
  targetWorkflowStateId: string,
  overIssueId: string | null,
) {
  const sourceColumnIndex = board.columns.findIndex((column) =>
    column.issues.some((issue) => issue.id === issueId),
  )
  const targetColumnIndex = board.columns.findIndex(
    (column) => column.workflowState.id === targetWorkflowStateId,
  )
  if (sourceColumnIndex < 0 || targetColumnIndex < 0) {
    return null
  }

  const sourceColumn = board.columns[sourceColumnIndex]
  const targetColumn = board.columns[targetColumnIndex]
  const sourceIssueIndex = sourceColumn.issues.findIndex((issue) => issue.id === issueId)
  const draggedIssue = sourceColumn.issues[sourceIssueIndex]
  if (!draggedIssue) {
    return null
  }

  if (sourceColumnIndex === targetColumnIndex) {
    const targetIssueIndex = overIssueId
      ? sourceColumn.issues.findIndex((issue) => issue.id === overIssueId)
      : sourceColumn.issues.length - 1
    if (targetIssueIndex < 0 || targetIssueIndex === sourceIssueIndex) {
      return null
    }

    const reorderedIssues = withTemporaryMovedIssuePosition(
      arrayMove(sourceColumn.issues, sourceIssueIndex, targetIssueIndex),
      issueId,
    )
    return {
      ...board,
      columns: board.columns.map((column, index) =>
        index === sourceColumnIndex ? { ...column, issues: reorderedIssues } : column,
      ),
    }
  }

  const targetIssueIndex = overIssueId
    ? targetColumn.issues.findIndex((issue) => issue.id === overIssueId)
    : targetColumn.issues.length
  if (targetIssueIndex < 0) {
    return null
  }

  const nextTargetIssues = [...targetColumn.issues]
  nextTargetIssues.splice(targetIssueIndex, 0, {
    ...draggedIssue,
    status: targetColumn.workflowState.category,
    workflowState: targetColumn.workflowState,
  })
  const reorderedTargetIssues = withTemporaryMovedIssuePosition(nextTargetIssues, issueId)
  const nextSourceIssues = sourceColumn.issues.filter((issue) => issue.id !== issueId)

  return {
    ...board,
    columns: board.columns.map((column, index) => {
      if (index === sourceColumnIndex) {
        return { ...column, issues: nextSourceIssues }
      }
      if (index === targetColumnIndex) {
        return { ...column, issues: reorderedTargetIssues }
      }
      return column
    }),
  }
}

export function boardIssueNeighbors(
  board: ProjectBoard,
  workflowStateId: string,
  issueId: string,
) {
  const column = board.columns.find(
    (candidate) => candidate.workflowState.id === workflowStateId,
  )
  const issueIndex = column?.issues.findIndex((issue) => issue.id === issueId) ?? -1
  if (!column || issueIndex < 0) {
    return null
  }
  return {
    previousIssueId: column.issues[issueIndex - 1]?.id ?? null,
    nextIssueId: column.issues[issueIndex + 1]?.id ?? null,
  }
}

export function applyBoardReorderResult(
  board: ProjectBoard | undefined,
  result: ReorderIssuesResponse,
) {
  if (!board) {
    return board
  }
  return {
    ...board,
    columns: board.columns.map((column) => ({
      ...column,
      issues: column.issues.map((issue) =>
        issue.id === result.issueId
          ? { ...issue, boardPosition: result.boardPosition }
          : issue,
      ),
    })),
  }
}

function withTemporaryMovedIssuePosition(issues: IssueSummary[], movedIssueId: string) {
  const movedIssueIndex = issues.findIndex((issue) => issue.id === movedIssueId)
  if (movedIssueIndex < 0) {
    return issues
  }
  const previousPosition = issues[movedIssueIndex - 1]?.boardPosition
  const nextPosition = issues[movedIssueIndex + 1]?.boardPosition
  let boardPosition = 10_000
  if (previousPosition !== undefined && nextPosition !== undefined) {
    boardPosition = previousPosition + (nextPosition - previousPosition) / 2
  } else if (previousPosition !== undefined) {
    boardPosition = previousPosition + 10_000
  } else if (nextPosition !== undefined) {
    boardPosition = nextPosition / 2
  }
  return issues.map((issue, index) =>
    index === movedIssueIndex ? { ...issue, boardPosition } : issue,
  )
}

export function groupIssuesByWorkflowState(
  issues: IssueSummary[],
  workflowStates: ProjectWorkflowState[],
  workflowFilter: IssueWorkflowFilter,
) {
  if (workflowFilter === 'ARCHIVED') {
    return [
      {
        status: 'ARCHIVED',
        workflowState: null,
        label: 'Archived',
        issues,
      },
    ]
  }

  const visibleWorkflowStates =
    workflowFilter === 'ACTIVE'
      ? workflowStates
      : workflowStates.filter((workflowState) => workflowState.id === workflowFilter)
  const grouped = new Map<string, IssueSummary[]>()

  visibleWorkflowStates.forEach((workflowState) => grouped.set(workflowState.id, []))
  issues.forEach((issue) => {
    const workflowState = issue.workflowState
    if (!grouped.has(workflowState.id)) {
      grouped.set(workflowState.id, [])
    }

    const group = grouped.get(workflowState.id) ?? []
    group.push(issue)
    grouped.set(workflowState.id, group)
  })

  const knownWorkflowStateIds = new Set(workflowStates.map((workflowState) => workflowState.id))
  const dynamicWorkflowStates = issues
    .map((issue) => issue.workflowState)
    .filter((workflowState, index, allWorkflowStates) =>
      !knownWorkflowStateIds.has(workflowState.id) &&
      allWorkflowStates.findIndex((candidate) => candidate.id === workflowState.id) === index,
    )
  const groups = [...visibleWorkflowStates, ...dynamicWorkflowStates]

  return groups.map((workflowState) => ({
    status: workflowState.category,
    workflowState,
    label: workflowState.name,
    issues: grouped.get(workflowState.id) ?? [],
  }))
}

export function defaultWorkflowStateIdForStatus(
  workflowStates: ProjectWorkflowState[],
  status: IssueStatus,
) {
  const category = status === 'DONE' || status === 'IN_PROGRESS' ? status : 'TODO'
  return workflowStates.find((workflowState) => workflowState.category === category)?.id ?? ''
}
