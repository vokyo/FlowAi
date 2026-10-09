import { MemoryRouter, Route, Routes } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createAccessToken, listAccessTokens, revokeAccessToken } from '@/api/access-token-api'
import { getCurrentSession } from '@/api/auth-api'
import { listProjects } from '@/api/work-api'
import { SettingsPage } from './SettingsPage'

vi.mock('@/api/auth-api', () => ({
  getCurrentSession: vi.fn(),
  changePassword: vi.fn(),
  revokeAllSessions: vi.fn(),
  updateProfile: vi.fn(),
}))

vi.mock('@/api/access-token-api', () => ({
  ACCESS_TOKEN_LIFETIMES: [30, 90, 365],
  createAccessToken: vi.fn(),
  listAccessTokens: vi.fn(async () => []),
  revokeAccessToken: vi.fn(async () => undefined),
}))

vi.mock('@/api/work-api', () => ({
  listProjects: vi.fn(),
  listWorkspaceMembers: vi.fn(async () => []),
}))

vi.mock('@/api/workspace-api', () => ({
  removeWorkspaceMember: vi.fn(),
  updateWorkspaceMember: vi.fn(),
}))

function mockSession() {
  vi.mocked(getCurrentSession).mockResolvedValue({
    user: { id: 'user-1', email: 'viewer@example.com', displayName: 'Viewer' },
    workspace: { id: 'workspace-1', name: 'Workspace', slug: 'workspace', role: 'MEMBER' },
  })
  vi.mocked(listProjects).mockResolvedValue([{
    id: 'project-1',
    name: 'Apollo',
    description: null,
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-07-01T00:00:00Z',
    archivedAt: null,
  }])
}

function renderSettings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/app/settings']}>
        <Routes>
          <Route path="/app/settings" element={<SettingsPage onSessionChanged={vi.fn()} />} />
          <Route
            path="/app/workspaces/:workspaceId/projects/:projectId/settings"
            element={<div>project settings route</div>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSession()
  })

  // Project settings moved to their own route. This page keeps the account and
  // workspace scopes only, and must not ask which project you meant.
  it('keeps only account and workspace settings', async () => {
    renderSettings()

    expect(await screen.findByRole('heading', { name: 'Account' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Workspace members' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Project')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Delete project/ })).not.toBeInTheDocument()
  })

  // Anyone landing here out of habit needs a route onward, not a dead end.
  it('sends each project to its own settings route', async () => {
    renderSettings()

    await userEvent.click(await screen.findByLabelText('Open settings for Apollo'))

    expect(await screen.findByText('project settings route')).toBeInTheDocument()
  })

  // Only a hash of a token is kept, so the page is the one chance to copy it.
  it('shows a new access token once and lists it without the token', async () => {
    vi.mocked(createAccessToken).mockResolvedValue({
      id: 'token-1',
      name: 'Claude Code',
      token: 'flowai_pat_secret-value',
      createdAt: '2026-10-09T00:00:00Z',
      expiresAt: '2027-01-07T00:00:00Z',
    })
    vi.mocked(listAccessTokens)
      .mockResolvedValueOnce([])
      .mockResolvedValue([{
        id: 'token-1',
        name: 'Claude Code',
        createdAt: '2026-10-09T00:00:00Z',
        expiresAt: '2027-01-07T00:00:00Z',
        lastUsedAt: null,
        expired: false,
      }])
    renderSettings()

    await userEvent.type(await screen.findByLabelText('Token name'), '  Claude Code  ')
    await userEvent.selectOptions(screen.getByLabelText('Expires after'), '30')
    await userEvent.click(screen.getByRole('button', { name: /Create token/ }))

    expect(screen.getByText(`${window.location.origin}/api/mcp`)).toBeInTheDocument()
    expect(createAccessToken).toHaveBeenCalledWith({ name: 'Claude Code', lifetimeDays: 30 })
    expect(await screen.findByText('flowai_pat_secret-value')).toBeInTheDocument()
    expect(screen.getByText('It will not be shown again.', { exact: false })).toBeInTheDocument()
    expect(await screen.findByText(/never used/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByText('flowai_pat_secret-value')).not.toBeInTheDocument()
  })

  it('revokes an access token only once the user confirms', async () => {
    vi.mocked(listAccessTokens).mockResolvedValue([{
      id: 'token-1',
      name: 'Cursor',
      createdAt: '2026-10-09T00:00:00Z',
      expiresAt: '2027-01-07T00:00:00Z',
      lastUsedAt: '2026-10-09T01:00:00Z',
      expired: false,
    }])
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    renderSettings()

    const revoke = await screen.findByRole('button', { name: 'Revoke Cursor' })
    await userEvent.click(revoke)
    expect(revokeAccessToken).not.toHaveBeenCalled()

    await userEvent.click(revoke)
    expect(revokeAccessToken).toHaveBeenCalledWith('token-1')
    confirm.mockRestore()
  })
})
