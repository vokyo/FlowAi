import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listAgentRuns, startAgentRun } from '@/api/agent-api'
import { ApiError } from '@/api/client'
import { PlanningAgentView } from './PlanningAgentView'

vi.mock('@/api/agent-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/agent-api')>()),
  listAgentRuns: vi.fn(),
  startAgentRun: vi.fn(),
}))

describe('PlanningAgentView', () => {
  beforeEach(() => {
    vi.mocked(listAgentRuns).mockResolvedValue([])
  })

  it('starts a run from a goal and opens it', async () => {
    vi.mocked(startAgentRun).mockResolvedValue({
      runId: 'run-1', status: 'PLANNED', version: 1, approvable: true, contentHash: 'a'.repeat(64),
      plan: { overview: 'A plan', existingIssues: [], items: [] }, stats: { decisionRounds: 2, toolCalls: 3 },
    })
    const onOpenRun = vi.fn()
    renderView({ onOpenRun })

    const generate = screen.getByRole('button', { name: 'Generate plan' })
    expect(generate).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Goal'), '  Plan the login work  ')
    await userEvent.click(generate)

    expect(startAgentRun).toHaveBeenCalledWith('project-1', 'Plan the login work')
    expect(onOpenRun).toHaveBeenCalledWith('run-1')
    expect(screen.getByLabelText('Goal')).toHaveValue('')
  })

  it('says what the agent was missing instead of opening a run', async () => {
    vi.mocked(startAgentRun).mockResolvedValue({
      runId: 'run-2', status: 'INSUFFICIENT_INFO', missing: ['Which team owns the login service'],
    })
    const onOpenRun = vi.fn()
    renderView({ onOpenRun })

    await userEvent.type(screen.getByLabelText('Goal'), 'Fix login')
    await userEvent.click(screen.getByRole('button', { name: 'Generate plan' }))

    expect(await screen.findByText('Which team owns the login service')).toBeInTheDocument()
    expect(onOpenRun).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Goal')).toHaveValue('Fix login')
  })

  it('explains why a run was refused', async () => {
    vi.mocked(startAgentRun).mockRejectedValue(
      new ApiError('Planning run in progress', 409, { code: 'AI_AGENT_RUN_IN_PROGRESS' }))
    renderView()

    await userEvent.type(screen.getByLabelText('Goal'), 'Plan the login work')
    await userEvent.click(screen.getByRole('button', { name: 'Generate plan' }))

    expect(await screen.findByText('You already have a run going on this project. Wait for it to finish.')).toBeInTheDocument()
  })

  it('lists the recent runs and opens one', async () => {
    vi.mocked(listAgentRuns).mockResolvedValue([
      { runId: 'run-new', projectId: 'project-1', goal: 'Plan the billing export', state: 'REVIEWING', latestVersion: 2, createdAt: '2026-10-09T02:00:00Z', updatedAt: '2026-10-09T02:00:00Z' },
      { runId: 'run-old', projectId: 'project-1', goal: 'Plan the login work', state: 'APPROVED', latestVersion: 1, createdAt: '2026-10-08T02:00:00Z', updatedAt: '2026-10-08T02:00:00Z' },
    ])
    const onOpenRun = vi.fn()
    renderView({ onOpenRun })

    expect(await screen.findByText('Waiting for review')).toBeInTheDocument()
    expect(screen.getByText('Approved')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Plan the login work/ }))

    expect(listAgentRuns).toHaveBeenCalledWith('project-1')
    expect(onOpenRun).toHaveBeenCalledWith('run-old')
  })

  it('cannot start a run when the deployment has no agent', async () => {
    renderView({ available: false })

    expect(screen.getByText(/The planning agent is not running on this deployment/)).toBeInTheDocument()
    expect(screen.getByLabelText('Goal')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Generate plan' })).toBeDisabled()
    expect(await screen.findByText('No runs on this project yet.')).toBeInTheDocument()
  })
})

function renderView({ available = true, onOpenRun = vi.fn() }: { available?: boolean; onOpenRun?: (runId: string | null) => void } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <PlanningAgentView
        workspaceId="workspace-1"
        project={null}
        projectId="project-1"
        runId={null}
        available={available}
        isLoadingStatus={false}
        onOpenRun={onOpenRun}
      />
    </QueryClientProvider>,
  )
}
