import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/client', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}))

import { api } from '@/api/client'
import { listWorkspaceInvitations } from '@/workspace/workspace-api'
import {
  getBoardColumnPage,
  listIssueActivities,
  listIssueComments,
  listIssues,
} from './work-api'

describe('paginated work APIs', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset()
    vi.mocked(api.get).mockResolvedValue({ items: [], nextCursor: null })
  })

  it('preserves issue filters and sends cursor and limit', () => {
    void listIssues(
      'project-1',
      { workflowStateId: 'doing', priority: 'HIGH', q: 'release bug' },
      'opaque+/=',
      100,
    )

    expect(api.get).toHaveBeenCalledWith(
      '/issues?projectId=project-1&workflowStateId=doing&priority=HIGH&q=release+bug&cursor=opaque%2B%2F%3D&limit=100',
    )
  })

  it('uses independent cursor endpoints for board columns, comments, and activities', () => {
    void getBoardColumnPage('project-1', 'state-1', 'board-cursor')
    void listIssueComments('issue-1', 'comment-cursor')
    void listIssueActivities('issue-1', 'activity-cursor')

    expect(api.get).toHaveBeenNthCalledWith(
      1,
      '/issues/board/states/state-1?projectId=project-1&limit=50&cursor=board-cursor',
    )
    expect(api.get).toHaveBeenNthCalledWith(
      2,
      '/issues/issue-1/comments?limit=50&cursor=comment-cursor',
    )
    expect(api.get).toHaveBeenNthCalledWith(
      3,
      '/issues/issue-1/activities?limit=50&cursor=activity-cursor',
    )
  })

  it('paginates invitation history by workspace query cursor', () => {
    void listWorkspaceInvitations('invite-cursor', 75)

    expect(api.get).toHaveBeenCalledWith(
      '/workspaces/current/invitations?limit=75&cursor=invite-cursor',
    )
  })
})
