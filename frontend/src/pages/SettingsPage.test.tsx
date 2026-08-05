import { MemoryRouter, Route, Routes } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCurrentSession } from '@/api/auth-api'
import { listProjects } from '@/api/work-api'
import { SettingsPage } from './SettingsPage'

vi.mock('@/api/auth-api', () => ({
  getCurrentSession: vi.fn(),
  changePassword: vi.fn(),
  revokeAllSessions: vi.fn(),
  updateProfile: vi.fn(),
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
})
