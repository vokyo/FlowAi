import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveAgentRun,
  cancelAgentRun,
  getAgentRun,
  reviseAgentRun,
  type AgentPlanVersion,
  type AgentRunDetail,
  type ProjectPlan,
} from '@/api/agent-api'
import { getIssue, listProjectMembers, type IssueDetail, type ProjectMember } from '@/api/work-api'
import { AgentRunPanel } from './AgentRunPanel'

vi.mock('@/api/agent-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/agent-api')>()),
  approveAgentRun: vi.fn(),
  cancelAgentRun: vi.fn(),
  getAgentRun: vi.fn(),
  reviseAgentRun: vi.fn(),
}))

vi.mock('@/api/work-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/work-api')>()),
  getIssue: vi.fn(),
  listProjectMembers: vi.fn(),
}))

const ISSUE_TITLES: Record<string, string> = {
  'issue-existing': 'Reset passwords by email',
  'issue-created': 'Add rate limiting to login',
}

const plan: ProjectPlan = {
  overview: 'Get the login flow ready for the review',
  existingIssues: [{ issueId: 'issue-existing', reason: 'Already covers the password reset' }],
  items: [
    {
      clientItemId: 'item-1',
      title: 'Add rate limiting to login',
      description: 'Five attempts a minute',
      priority: 'HIGH',
      suggestedAssigneeUserId: 'user-maya',
      dueDate: '2026-10-20',
    },
    {
      clientItemId: 'item-2',
      title: 'Write the login runbook',
      description: null,
      priority: null,
      suggestedAssigneeUserId: null,
      dueDate: null,
    },
  ],
}

function version(number: number, overrides: Partial<AgentPlanVersion> = {}): AgentPlanVersion {
  return {
    version: number,
    approvable: true,
    rejectionReason: null,
    contentHash: String(number).repeat(64),
    plan,
    createdIssueIds: [],
    createdAt: '2026-10-09T01:00:00Z',
    ...overrides,
  }
}

function run(overrides: Partial<AgentRunDetail> = {}): AgentRunDetail {
  return {
    runId: 'run-1',
    projectId: 'project-1',
    goal: 'Get the login flow ready for the security review',
    generatedOn: '2026-10-09',
    state: 'REVIEWING',
    latestVersion: 1,
    versions: [version(1)],
    createdAt: '2026-10-09T01:00:00Z',
    updatedAt: '2026-10-09T01:00:00Z',
    ...overrides,
  }
}

describe('AgentRunPanel', () => {
  beforeEach(() => {
    vi.mocked(getAgentRun).mockResolvedValue(run())
    vi.mocked(getIssue).mockImplementation(async (issueId) =>
      ({ id: issueId, title: ISSUE_TITLES[issueId] ?? issueId }) as IssueDetail)
    vi.mocked(listProjectMembers).mockResolvedValue([
      { id: 'member-1', userId: 'user-maya', email: 'maya@example.com', displayName: 'Maya Chen', role: 'MEMBER', status: 'ACTIVE', joinedAt: '2026-09-01T00:00:00Z' } as ProjectMember,
    ])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the plan with the issues and people it names', async () => {
    renderPanel()

    expect(await screen.findByRole('heading', { name: 'Get the login flow ready for the security review' })).toBeInTheDocument()
    expect(screen.getByText('Get the login flow ready for the review')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Reset passwords by email' })).toHaveAttribute(
      'href', '/app/workspaces/workspace-1/projects/project-1/issues/issue-existing')
    expect(screen.getByText('Already covers the password reset')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New issues to create (2)' })).toBeInTheDocument()
    expect(await screen.findByText('Maya Chen')).toBeInTheDocument()
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
    expect(screen.getByText('No due date')).toBeInTheDocument()
    expect(screen.getByText(/^Due /)).toBeInTheDocument()
  })

  it('approves the version the user read and lists the issues it created', async () => {
    vi.mocked(approveAgentRun).mockResolvedValue({
      runId: 'run-1', version: 1, createdIssueIds: ['issue-created'], approvedAt: '2026-10-09T02:00:00Z',
    })
    vi.mocked(getAgentRun)
      .mockResolvedValueOnce(run())
      .mockResolvedValue(run({ state: 'APPROVED', versions: [version(1, { createdIssueIds: ['issue-created'] })] }))
    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: 'Approve and create 2 issues' }))

    expect(approveAgentRun).toHaveBeenCalledWith('run-1', { version: 1, contentHash: '1'.repeat(64) })
    expect(await screen.findByText('Approved. 1 issue was created:')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Add rate limiting to login' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revise plan' })).not.toBeInTheDocument()
  })

  it('revises the latest version with the feedback and shows the new one', async () => {
    vi.mocked(reviseAgentRun).mockResolvedValue({
      runId: 'run-1', status: 'PLANNED', version: 2, approvable: true, contentHash: '2'.repeat(64), plan,
      stats: { decisionRounds: 1, toolCalls: 2 },
    })
    vi.mocked(getAgentRun)
      .mockResolvedValueOnce(run())
      .mockResolvedValue(run({ latestVersion: 2, versions: [version(1), version(2)] }))
    renderPanel()

    const revise = await screen.findByRole('button', { name: 'Revise plan' })
    expect(revise).toBeDisabled()
    await userEvent.type(screen.getByLabelText('What should change?'), '  Split the API work  ')
    await userEvent.click(revise)

    expect(reviseAgentRun).toHaveBeenCalledWith('run-1', { basedOnVersion: 1, feedback: 'Split the API work' })
    expect(await screen.findByRole('button', { name: 'Version 2', pressed: true })).toBeInTheDocument()
    expect(screen.getByText('The agent made 2 lookups over 1 round for this version.')).toBeInTheDocument()
    expect(screen.getByLabelText('What should change?')).toHaveValue('')
  })

  it('says what a revision was missing and keeps the version it had', async () => {
    vi.mocked(reviseAgentRun).mockResolvedValue({
      runId: 'run-1', status: 'INSUFFICIENT_INFO', missing: ['Which login methods are in scope'],
    })
    renderPanel()

    await userEvent.type(await screen.findByLabelText('What should change?'), 'Cover every login method')
    await userEvent.click(screen.getByRole('button', { name: 'Revise plan' }))

    expect(await screen.findByText(/version 1 is unchanged/)).toBeInTheDocument()
    expect(screen.getByText('Which login methods are in scope')).toBeInTheDocument()
  })

  it('keeps a version that fails the checks from being approved', async () => {
    vi.mocked(getAgentRun).mockResolvedValue(run({
      versions: [version(1, { approvable: false, rejectionReason: 'Maya Chen is no longer a member of the project' })],
    }))
    renderPanel()

    expect(await screen.findByText(/Maya Chen is no longer a member of the project/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve and create 2 issues' })).toBeDisabled()
  })

  it('shows replaced versions without letting them be revised or approved', async () => {
    vi.mocked(getAgentRun).mockResolvedValue(run({ latestVersion: 2, versions: [version(1), version(2)] }))
    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: 'Version 1' }))

    expect(screen.getByText(/Version 1 was replaced by version 2\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revise plan' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Approve and create/ })).not.toBeInTheDocument()
  })

  it('closes revising once the run has all its versions', async () => {
    vi.mocked(getAgentRun).mockResolvedValue(run({
      latestVersion: 5, versions: [version(1), version(2), version(3), version(4), version(5)],
    }))
    renderPanel()

    expect(await screen.findByText('This run has reached its 5 versions. Approve it or cancel it.')).toBeInTheDocument()
    expect(screen.getByLabelText('What should change?')).toBeDisabled()
  })

  it('asks before cancelling a run', async () => {
    vi.mocked(cancelAgentRun).mockResolvedValue(run({ state: 'CANCELLED' }))
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    renderPanel()

    const cancel = await screen.findByRole('button', { name: 'Cancel run' })
    await userEvent.click(cancel)
    expect(cancelAgentRun).not.toHaveBeenCalled()

    await userEvent.click(cancel)
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(cancelAgentRun).toHaveBeenCalledWith('run-1')
    expect(await screen.findByText('This run was cancelled. Nothing from its plan was created.')).toBeInTheDocument()
  })

  it('cannot revise without the agent but can still approve', async () => {
    renderPanel({ available: false })

    const footer = (await screen.findByRole('button', { name: 'Revise plan' })).closest('footer')!
    expect(within(footer).getByText(/Revising needs the planning agent/)).toBeInTheDocument()
    expect(within(footer).getByLabelText('What should change?')).toBeDisabled()
    expect(within(footer).getByRole('button', { name: 'Approve and create 2 issues' })).toBeEnabled()
  })
})

function renderPanel({ available = true }: { available?: boolean } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AgentRunPanel
          workspaceId="workspace-1"
          projectId="project-1"
          runId="run-1"
          available={available}
          onClose={vi.fn()}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
