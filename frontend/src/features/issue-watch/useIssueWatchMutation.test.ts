import { QueryClient, QueryClientProvider, type InfiniteData } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { createElement, type ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CursorPage } from '@/api/pagination'
import {
  watchIssue,
  type IssueDetail,
  type IssueSummary,
  type IssueWatchState,
  type ProjectBoard,
  type ProjectWorkflowState,
} from '@/api/work-api'
import { queryKeys } from '@/lib/query-keys'
import { useIssueWatchMutation } from './useIssueWatchMutation'

vi.mock('@/api/work-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/work-api')>()),
  watchIssue: vi.fn(),
  unwatchIssue: vi.fn(),
}))

type IssueListData = InfiniteData<CursorPage<IssueSummary>>

const user = { id: 'user-1', email: 'owner@example.com', displayName: 'Owner' }
const workflowState: ProjectWorkflowState = {
  id: 'state-1',
  projectId: 'project-1',
  name: 'Todo',
  category: 'TODO',
  position: 0,
  createdAt: '2026-07-14T00:00:00Z',
  updatedAt: '2026-07-14T00:00:00Z',
}

const detailKey = queryKeys.issue('workspace-1', 'issue-1')
const listKey = queryKeys.issueList('workspace-1', 'project-1', ['all'])
const boardKey = queryKeys.board('workspace-1', 'project-1')

function issue(id: string): IssueSummary {
  return {
    id,
    projectId: 'project-1',
    title: `Issue ${id}`,
    status: 'TODO',
    workflowState,
    labels: [],
    creator: user,
    boardPosition: 10_000,
    createdAt: '2026-07-14T00:00:00Z',
    updatedAt: '2026-07-14T00:00:00Z',
    watched: false,
  }
}

function seededClient() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  queryClient.setQueryData<IssueDetail>(detailKey, { ...issue('issue-1'), watcherCount: 1 })
  queryClient.setQueryData<IssueListData>(listKey, {
    pages: [{ items: [issue('issue-1'), issue('issue-2')], nextCursor: null }],
    pageParams: [null],
  })
  queryClient.setQueryData<ProjectBoard>(boardKey, {
    projectId: 'project-1',
    columns: [{ workflowState, issues: [issue('issue-1'), issue('issue-2')], nextCursor: null }],
  })
  return queryClient
}

function renderWatchMutation(queryClient: QueryClient) {
  return renderHook(() => useIssueWatchMutation('workspace-1'), {
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  })
}

function cachedWatchState(queryClient: QueryClient) {
  const detail = queryClient.getQueryData<IssueDetail>(detailKey)
  const listItems = queryClient.getQueryData<IssueListData>(listKey)?.pages[0].items
  const boardIssues = queryClient.getQueryData<ProjectBoard>(boardKey)?.columns[0].issues
  return {
    detail: detail?.watched,
    watcherCount: detail?.watcherCount,
    list: listItems?.map((item) => item.watched),
    board: boardIssues?.map((item) => item.watched),
  }
}

describe('useIssueWatchMutation', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('stars the issue in every cache before the server answers and puts them back when it fails', async () => {
    let rejectRequest!: (error: Error) => void
    vi.mocked(watchIssue).mockReturnValue(new Promise<IssueWatchState>((_, reject) => {
      rejectRequest = reject
    }))
    const queryClient = seededClient()
    const { result } = renderWatchMutation(queryClient)

    act(() => result.current.mutate({ issueId: 'issue-1', projectId: 'project-1', watched: false }))

    await waitFor(() => expect(cachedWatchState(queryClient)).toEqual({
      detail: true,
      watcherCount: 2,
      list: [true, false],
      board: [true, false],
    }))

    rejectRequest(new Error('network down'))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(cachedWatchState(queryClient)).toEqual({
      detail: false,
      watcherCount: 1,
      list: [false, false],
      board: [false, false],
    })
  })

  it('replaces the guessed count with the one the server returns', async () => {
    vi.mocked(watchIssue).mockResolvedValue({ watched: true, watcherCount: 3 })
    const queryClient = seededClient()
    const { result } = renderWatchMutation(queryClient)

    act(() => result.current.mutate({ issueId: 'issue-1', projectId: 'project-1', watched: false }))

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(cachedWatchState(queryClient)).toEqual({
      detail: true,
      watcherCount: 3,
      list: [true, false],
      board: [true, false],
    })
  })
})
