import { MemoryRouter, Route, Routes } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCurrentSession } from '@/api/auth-api'
import { getProject, listProjectMembers } from '@/api/work-api'
import { ProjectSettingsPage } from './ProjectSettingsPage'

vi.mock('@/api/auth-api', () => ({
  getCurrentSession: vi.fn(),
}))

vi.mock('@/api/work-api', () => ({
  getProject: vi.fn(),
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

const VIEWER_ID = 'user-1'
const WORKSPACE_ID = 'workspace-1'
const PROJECT_ID = 'project-1'

function mockSession() {
  vi.mocked(getCurrentSession).mockResolvedValue({
    user: { id: VIEWER_ID, email: 'viewer@example.com', displayName: 'Viewer' },
    workspace: { id: WORKSPACE_ID, name: 'Workspace', slug: 'workspace', role: 'MEMBER' },
  })
  vi.mocked(getProject).mockResolvedValue({
    id: PROJECT_ID,
    name: 'Apollo',
    description: null,
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-07-01T00:00:00Z',
    archivedAt: null,
  })
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

function renderProjectSettings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/app/workspaces/${WORKSPACE_ID}/projects/${PROJECT_ID}/settings`]}>
        <Routes>
          <Route
            path="/app/workspaces/:workspaceId/projects/:projectId/settings"
            element={<ProjectSettingsPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ProjectSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSession()
  })

  // The project comes from the route, not from a picker: that is the whole
  // reason this page exists apart from /app/settings.
  it('loads the project named in the route', async () => {
    mockViewerProjectRole('OWNER')
    renderProjectSettings()

    expect(await screen.findByRole('heading', { name: 'Apollo', level: 1 })).toBeInTheDocument()
    expect(getProject).toHaveBeenCalledWith(PROJECT_ID)
    expect(screen.queryByLabelText('Project')).not.toBeInTheDocument()
  })

  // The backend gates deleteProject on ProjectRole.OWNER. Rendering the control
  // for anyone else only buys the user a 403.
  it('hides the danger zone from a project member', async () => {
    mockViewerProjectRole('MEMBER')
    renderProjectSettings()

    expect(await screen.findByLabelText('Name')).toBeDisabled()
    expect(screen.queryByRole('button', { name: /Delete project/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Archive project/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Save project/ })).not.toBeInTheDocument()
  })

  it('shows the danger zone to the project owner', async () => {
    mockViewerProjectRole('OWNER')
    renderProjectSettings()

    expect(await screen.findByRole('button', { name: /Delete project/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Archive project/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Save project/ })).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toBeEnabled()
  })
})
