import { describe, expect, it } from 'vitest'
import type { IssueSummary, ProjectBoard, ProjectWorkflowState } from './work-api'
import { buildOptimisticBoard, filterProjectBoard } from './board-utils'

const todo: ProjectWorkflowState = {
  id: 'todo',
  projectId: 'project-1',
  name: 'Todo',
  category: 'TODO',
  position: 0,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const doing: ProjectWorkflowState = {
  ...todo,
  id: 'doing',
  name: 'Doing',
  category: 'IN_PROGRESS',
  position: 1,
}

function issue(
  id: string,
  workflowState: ProjectWorkflowState,
  assigneeId?: string,
): IssueSummary {
  return {
    id,
    projectId: 'project-1',
    title: id,
    status: workflowState.category,
    workflowState,
    priority: 'MEDIUM',
    labels: [],
    creator: { id: 'creator', email: 'creator@example.com', displayName: 'Creator' },
    assignee: assigneeId
      ? { id: assigneeId, email: `${assigneeId}@example.com`, displayName: assigneeId }
      : null,
    boardPosition: 10_000,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

describe('filtered board reordering', () => {
  it('calculates a My issues move from the complete board so hidden issues remain in the payload', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [
        { workflowState: todo, issues: [issue('mine', todo, 'me'), issue('other', todo, 'other')] },
        { workflowState: doing, issues: [issue('hidden-target', doing, 'other')] },
      ],
    }

    const visibleBoard = filterProjectBoard(board, 'MINE', 'me')
    expect(visibleBoard?.columns.map((column) => column.issues.map(({ id }) => id))).toEqual([
      ['mine'],
      [],
    ])

    const optimisticBoard = buildOptimisticBoard(board, 'mine', 'doing', null)
    expect(optimisticBoard?.columns.map((column) => column.issues.map(({ id }) => id))).toEqual([
      ['other'],
      ['hidden-target', 'mine'],
    ])
  })

  it('calculates an Unassigned move while retaining assigned issues in the target column', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [
        { workflowState: todo, issues: [issue('unassigned', todo)] },
        { workflowState: doing, issues: [issue('assigned', doing, 'other')] },
      ],
    }

    const optimisticBoard = buildOptimisticBoard(board, 'unassigned', 'doing', null)
    expect(optimisticBoard?.columns[1].issues.map(({ id }) => id)).toEqual([
      'assigned',
      'unassigned',
    ])
  })
})
