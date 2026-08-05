import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCurrentSession } from '@/api/auth-api'
import { listProjectMembers, listProjects } from '@/api/work-api'
import { SettingsPage } from './SettingsPage'

vi.mock('@/api/auth-api', () => ({
  getCurrentSession: vi.fn(),
  changePassword: vi.fn(),
  revokeAllSessions: vi.fn(),
  updateProfile: vi.fn(),
}))

vi.mock('@/api/work-api', () => ({
  listProjects: vi.fn(),
  listProjectMembers: vi.fn(),
  listWorkspaceMembers: vi.fn(async () => []),
  listProjectLabels: vi.fn(async () => []),
  listProjectWorkflowStates: vi.fn(async () => []),
  archiveProject: vi.fn(),
  restoreProject: vi.fn(),
  deleteProject: vi.fn(),
  updateProject: vi.fn(),
  createProjectLabel: vi.fn(),
  updateProjectLabel: vi.fn(),
  deleteProjectLabel: vi.fn(),
  createProjectWorkflowState: vi.fn(),
  updateProjectWorkflowState: vi.fn(),
  deleteProjectWorkflowState: vi.fn(),
}))

vi.mock('@/api/workspace-api', () => ({
  removeWorkspaceMember: vi.fn(),
  updateWorkspaceMember: vi.fn(),
}))

const VIEWER_ID = 'user-1'

function mockSession() {
  vi.mocked(getCurrentSession).mockResolvedValue({
    user: { id: VIEWER_ID, email: 'viewer@example.com', displayName: 'Viewer' },
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

/** The viewer's own membership row, at the role under test. */
function mockViewerProjectRole(role: 'OWNER' | 'MEMBER') {
  vi.mocked(listProjectMembers).mockResolvedValue([{
    id: 'membership-1',
    userId: VIEWER_ID,
    email: 'viewer@example.com',
    displayName: 'Viewer',
    role,
    status: 'ACTIVE',
    joinedAt: '2026-07-01T00:00:00Z',
  }])
}

function renderSettings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/app/settings']}>
        <SettingsPage onSessionChanged={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SettingsPage project administration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSession()
  })

  // The backend gates deleteProject on ProjectRole.OWNER. Rendering the control
  // for anyone else only buys the user a 403.
  it('hides the danger zone from a project member', async () => {
    mockViewerProjectRole('MEMBER')
    renderSettings()

    expect(await screen.findByLabelText('Project')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Delete project/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Archive project/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Save project/ })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toBeDisabled()
  })

  it('shows the danger zone to the project owner', async () => {
    mockViewerProjectRole('OWNER')
    renderSettings()

    expect(await screen.findByRole('button', { name: /Delete project/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Archive project/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Save project/ })).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toBeEnabled()
  })
})
