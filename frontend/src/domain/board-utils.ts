import { arrayMove } from '@dnd-kit/sortable'
import type { CursorPage } from '@/api/pagination'
import type {
  IssueStatus,
  IssueSummary,
  ProjectBoard,
  ProjectWorkflowState,
  ReorderIssuesResponse,
} from '@/api/work-api'

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

/** Where the pointer is during a drag, in board terms. */
export type BoardDropTarget = {
  workflowStateId: string
  /** The card being hovered, or null when the pointer is over the column itself. */
  issueId: string | null
  /** Whether the dragged card's centre is below the hovered card's centre. */
  after: boolean
}

export function issueColumnId(board: ProjectBoard, issueId: string) {
  return (
    board.columns.find((column) => column.issues.some((issue) => issue.id === issueId))
      ?.workflowState.id ?? null
  )
}

/**
 * Moves the dragged issue to the drop target on the board as displayed. This is
 * what the drag preview and the final drop both run.
 *
 * Within one column it is a sortable move: the issue takes the index of the
 * card it is over, so dragging down lands it after that card and dragging up
 * before it — the rule dnd-kit's vertical strategy animates during the drag.
 * Across columns it goes before or after the hovered card, by which half the
 * dragged card is over, or to the end when the pointer is over the column.
 *
 * Returns the same board when nothing moves, so callers can compare by identity.
 */
export function moveIssueOver(board: ProjectBoard, issueId: string, target: BoardDropTarget) {
  const sourceColumn = board.columns.find((column) =>
    column.issues.some((issue) => issue.id === issueId),
  )
  const targetColumn = board.columns.find(
    (column) => column.workflowState.id === target.workflowStateId,
  )
  if (!sourceColumn || !targetColumn) {
    return board
  }

  const sourceIndex = sourceColumn.issues.findIndex((issue) => issue.id === issueId)
  const overIndex = target.issueId
    ? targetColumn.issues.findIndex((issue) => issue.id === target.issueId)
    : -1

  if (sourceColumn === targetColumn) {
    const targetIndex = target.issueId ? overIndex : targetColumn.issues.length - 1
    if (targetIndex < 0 || targetIndex === sourceIndex) {
      return board
    }
    return replaceColumnIssues(board, {
      [targetColumn.workflowState.id]: arrayMove(targetColumn.issues, sourceIndex, targetIndex),
    })
  }

  const targetIndex = overIndex >= 0 ? overIndex + (target.after ? 1 : 0) : targetColumn.issues.length
  const targetIssues = [...targetColumn.issues]
  targetIssues.splice(targetIndex, 0, {
    ...sourceColumn.issues[sourceIndex],
    status: targetColumn.workflowState.category,
    workflowState: targetColumn.workflowState,
  })
  return replaceColumnIssues(board, {
    [sourceColumn.workflowState.id]: sourceColumn.issues.filter((issue) => issue.id !== issueId),
    [targetColumn.workflowState.id]: targetIssues,
  })
}

/**
 * The optimistic *complete* board for a drop that left `issueId` where it sits
 * on `shownBoard`.
 *
 * The two boards differ when a My issues or Unassigned filter hides cards. The
 * issue is anchored to its visible neighbours — just after the card shown above
 * it, else just before the card shown below it, else at the end of the column —
 * so the hidden issues keep their places around it and the reorder request
 * names its real neighbours.
 *
 * Returns null when the issue would land exactly where it already is.
 */
export function buildOptimisticBoard(
  completeBoard: ProjectBoard,
  shownBoard: ProjectBoard,
  issueId: string,
) {
  const shownColumn = shownBoard.columns.find((column) =>
    column.issues.some((issue) => issue.id === issueId),
  )
  const sourceColumn = completeBoard.columns.find((column) =>
    column.issues.some((issue) => issue.id === issueId),
  )
  const targetColumn = completeBoard.columns.find(
    (column) => column.workflowState.id === shownColumn?.workflowState.id,
  )
  if (!shownColumn || !sourceColumn || !targetColumn) {
    return null
  }

  const shownIndex = shownColumn.issues.findIndex((issue) => issue.id === issueId)
  const shownAboveId = shownColumn.issues[shownIndex - 1]?.id
  const shownBelowId = shownColumn.issues[shownIndex + 1]?.id
  const remaining = targetColumn.issues.filter((issue) => issue.id !== issueId)
  const aboveIndex = shownAboveId ? remaining.findIndex((issue) => issue.id === shownAboveId) : -1
  const belowIndex = shownBelowId ? remaining.findIndex((issue) => issue.id === shownBelowId) : -1
  const insertIndex =
    aboveIndex >= 0 ? aboveIndex + 1 : belowIndex >= 0 ? belowIndex : remaining.length

  if (
    sourceColumn === targetColumn &&
    insertIndex === sourceColumn.issues.findIndex((issue) => issue.id === issueId)
  ) {
    return null
  }

  const movedIssue = sourceColumn.issues.find((issue) => issue.id === issueId)!
  remaining.splice(insertIndex, 0, {
    ...movedIssue,
    status: targetColumn.workflowState.category,
    workflowState: targetColumn.workflowState,
  })
  return replaceColumnIssues(completeBoard, {
    [sourceColumn.workflowState.id]: sourceColumn.issues.filter((issue) => issue.id !== issueId),
    // Listed second so a same-column move keeps the reordered list.
    [targetColumn.workflowState.id]: withTemporaryMovedIssuePosition(remaining, issueId),
  })
}

function replaceColumnIssues(
  board: ProjectBoard,
  issuesByWorkflowStateId: Record<string, IssueSummary[]>,
) {
  return {
    ...board,
    columns: board.columns.map((column) => {
      const issues = issuesByWorkflowStateId[column.workflowState.id]
      return issues ? { ...column, issues } : column
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
