import { describe, expect, it } from 'vitest'
import type { IssueSummary, ProjectBoard, ProjectWorkflowState } from '@/api/work-api'
import {
  appendBoardColumnPage,
  applyBoardReorderResult,
  boardIssueNeighbors,
  buildOptimisticBoard,
  filterProjectBoard,
  moveIssueOver,
} from './board-utils'

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
        { workflowState: todo, issues: [issue('mine', todo, 'me'), issue('other', todo, 'other')], nextCursor: null },
        { workflowState: doing, issues: [issue('hidden-target', doing, 'other')], nextCursor: null },
      ],
    }

    const visibleBoard = filterProjectBoard(board, 'MINE', 'me')
    expect(visibleBoard?.columns.map((column) => column.issues.map(({ id }) => id))).toEqual([
      ['mine'],
      [],
    ])

    const shownBoard = moveIssueOver(visibleBoard!, 'mine', {
      workflowStateId: 'doing',
      issueId: null,
      after: false,
    })
    const optimisticBoard = buildOptimisticBoard(board, shownBoard, 'mine')
    expect(optimisticBoard?.columns.map((column) => column.issues.map(({ id }) => id))).toEqual([
      ['other'],
      ['hidden-target', 'mine'],
    ])
  })

  it('calculates an Unassigned move while retaining assigned issues in the target column', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [
        { workflowState: todo, issues: [issue('unassigned', todo)], nextCursor: null },
        { workflowState: doing, issues: [issue('assigned', doing, 'other')], nextCursor: null },
      ],
    }

    const shownBoard = moveIssueOver(filterProjectBoard(board, 'UNASSIGNED', 'me')!, 'unassigned', {
      workflowStateId: 'doing',
      issueId: null,
      after: false,
    })
    const optimisticBoard = buildOptimisticBoard(board, shownBoard, 'unassigned')
    expect(optimisticBoard?.columns[1].issues.map(({ id }) => id)).toEqual([
      'assigned',
      'unassigned',
    ])
  })

  it('merges a next column page without duplicating overlapping issues', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [
        { workflowState: todo, issues: [issue('one', todo), issue('two', todo)], nextCursor: 'page-2' },
      ],
    }

    const merged = appendBoardColumnPage(board, todo.id, {
      items: [issue('two', todo), issue('three', todo)],
      nextCursor: null,
    })

    expect(merged?.columns[0].issues.map(({ id }) => id)).toEqual(['one', 'two', 'three'])
    expect(merged?.columns[0].nextCursor).toBeNull()
  })

  it('derives the sparse reorder request from the complete optimistic board', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [{
        workflowState: todo,
        issues: [issue('one', todo), issue('two', todo), issue('three', todo)],
        nextCursor: null,
      }],
    }
    const shownBoard = moveIssueOver(board, 'three', {
      workflowStateId: todo.id,
      issueId: 'two',
      after: false,
    })
    const optimisticBoard = buildOptimisticBoard(board, shownBoard, 'three')

    expect(optimisticBoard?.columns[0].issues.map(({ id }) => id)).toEqual([
      'one',
      'three',
      'two',
    ])
    expect(boardIssueNeighbors(optimisticBoard!, todo.id, 'three')).toEqual({
      previousIssueId: 'one',
      nextIssueId: 'two',
    })
  })

  it('previews a cross-column move before or after the hovered card, by which half it is over', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [
        { workflowState: todo, issues: [issue('dragged', todo)], nextCursor: null },
        { workflowState: doing, issues: [issue('x', doing), issue('y', doing)], nextCursor: null },
      ],
    }

    const upperHalf = moveIssueOver(board, 'dragged', { workflowStateId: 'doing', issueId: 'x', after: false })
    const lowerHalf = moveIssueOver(board, 'dragged', { workflowStateId: 'doing', issueId: 'x', after: true })

    expect(upperHalf.columns.map((column) => column.issues.map(({ id }) => id))).toEqual([
      [],
      ['dragged', 'x', 'y'],
    ])
    expect(lowerHalf.columns[1].issues.map(({ id }) => id)).toEqual(['x', 'dragged', 'y'])
    expect(upperHalf.columns[1].issues[0].workflowState.id).toBe('doing')
    expect(upperHalf.columns[1].issues[0].status).toBe('IN_PROGRESS')
  })

  it('anchors a filtered drop to the visible card above it, leaving hidden issues where they were', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [
        { workflowState: todo, issues: [issue('mine-new', todo, 'me')], nextCursor: null },
        {
          workflowState: doing,
          issues: [issue('mine-a', doing, 'me'), issue('hidden', doing, 'other'), issue('mine-b', doing, 'me')],
          nextCursor: null,
        },
      ],
    }

    // Dropped between the two visible cards of My issues.
    const shownBoard = moveIssueOver(filterProjectBoard(board, 'MINE', 'me')!, 'mine-new', {
      workflowStateId: 'doing',
      issueId: 'mine-b',
      after: false,
    })
    const optimisticBoard = buildOptimisticBoard(board, shownBoard, 'mine-new')

    expect(optimisticBoard?.columns[1].issues.map(({ id }) => id)).toEqual([
      'mine-a',
      'mine-new',
      'hidden',
      'mine-b',
    ])
    expect(boardIssueNeighbors(optimisticBoard!, 'doing', 'mine-new')).toEqual({
      previousIssueId: 'mine-a',
      nextIssueId: 'hidden',
    })
  })

  it('treats a drop that leaves the issue in place as no move at all', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [{ workflowState: todo, issues: [issue('one', todo), issue('two', todo)], nextCursor: null }],
    }

    const shownBoard = moveIssueOver(board, 'one', { workflowStateId: todo.id, issueId: 'one', after: false })

    expect(shownBoard).toBe(board)
    expect(buildOptimisticBoard(board, shownBoard, 'one')).toBeNull()
  })

  it('applies the server board position without replacing loaded columns', () => {
    const board: ProjectBoard = {
      projectId: 'project-1',
      columns: [{ workflowState: todo, issues: [issue('one', todo)], nextCursor: 'page-2' }],
    }
    const updated = applyBoardReorderResult(board, {
      issueId: 'one',
      workflowStateId: todo.id,
      boardPosition: 5_000,
      rebalanced: false,
    })

    expect(updated?.columns[0].issues[0].boardPosition).toBe(5_000)
    expect(updated?.columns[0].nextCursor).toBe('page-2')
  })
})
