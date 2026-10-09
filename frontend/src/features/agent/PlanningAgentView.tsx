import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, Sparkles } from 'lucide-react'
import {
  AGENT_GOAL_MAX_LENGTH,
  listAgentRuns,
  startAgentRun,
  type AgentRunSummary,
} from '@/api/agent-api'
import type { Project } from '@/api/work-api'
import { Button } from '@/components/ui/button'
import { formatDate } from '@/ui/display-utils'
import { EmptyState, ErrorState, InlineNotice, InlineState } from '@/ui/feature-ui'
import { AgentErrorNotice } from './AgentErrorNotice'
import { AgentMissingNotice } from './AgentMissingNotice'
import { AgentRunPanel } from './AgentRunPanel'
import { AgentRunStateBadge } from './AgentRunStateBadge'
import { AgentWorking } from './AgentWorking'
import { agentQueryKeys } from './agent-query-keys'

type PlanningAgentViewProps = {
  workspaceId: string
  project: Project | null
  projectId: string
  runId: string | null
  available: boolean
  isLoadingStatus: boolean
  onOpenRun: (runId: string | null) => void
}

/**
 * The planning agent's page for one project: describe a goal, get a plan built from
 * the project's own issues and members, and review it. Nothing is written to the
 * project until a version is approved.
 */
export function PlanningAgentView({
  workspaceId,
  project,
  projectId,
  runId,
  available,
  isLoadingStatus,
  onOpenRun,
}: PlanningAgentViewProps) {
  return (
    <div className="content-page agent-page">
      <header className="content-header agent-header">
        <div>
          <p className="breadcrumb-line">
            <span>{project?.name ?? 'Project'}</span>
            <span aria-hidden="true">/</span>
            <span>Planning agent</span>
          </p>
          <h1>Planning agent</h1>
          <p>
            Describe a goal. The agent reads this project&apos;s issues and members and proposes a plan; nothing is
            created until you approve it.
          </p>
        </div>
      </header>

      {!available && !isLoadingStatus ? (
        <InlineNotice tone="warning">
          The planning agent is not running on this deployment, so no new plans can be made. Runs you already have
          can still be opened, approved or cancelled.
        </InlineNotice>
      ) : null}

      <div className="agent-layout" data-run-open={Boolean(runId)}>
        <div className="agent-sidebar">
          <StartRunForm
            workspaceId={workspaceId}
            projectId={projectId}
            available={available}
            isLoadingStatus={isLoadingStatus}
            onOpenRun={onOpenRun}
          />
          <RecentRuns workspaceId={workspaceId} projectId={projectId} openRunId={runId} onOpenRun={onOpenRun} />
        </div>
        <div className="agent-main">
          {runId ? (
            <AgentRunPanel
              key={runId}
              workspaceId={workspaceId}
              projectId={projectId}
              runId={runId}
              available={available}
              onClose={() => onOpenRun(null)}
            />
          ) : (
            <EmptyState title="No run open" body="Start a run with a goal, or open one of your recent runs." />
          )}
        </div>
      </div>
    </div>
  )
}

function StartRunForm({ workspaceId, projectId, available, isLoadingStatus, onOpenRun }: {
  workspaceId: string
  projectId: string
  available: boolean
  isLoadingStatus: boolean
  onOpenRun: (runId: string) => void
}) {
  const queryClient = useQueryClient()
  const [goal, setGoal] = useState('')
  const startMutation = useMutation({
    mutationFn: (nextGoal: string) => startAgentRun(projectId, nextGoal),
    onSuccess: async (response) => {
      if (response.status === 'PLANNED' && response.version) {
        queryClient.setQueryData(
          agentQueryKeys.stats(workspaceId, response.runId, response.version),
          response.stats ?? null,
        )
        setGoal('')
        onOpenRun(response.runId)
      }
      await queryClient.invalidateQueries({ queryKey: agentQueryKeys.runs(workspaceId, projectId) })
    },
  })

  return (
    <section className="agent-card" aria-labelledby="agent-start-title">
      <h2 id="agent-start-title"><Sparkles aria-hidden="true" /> New plan</h2>
      <form
        className="agent-start-form"
        onSubmit={(event) => {
          event.preventDefault()
          startMutation.mutate(goal.trim())
        }}
      >
        <label>
          Goal
          <textarea
            value={goal}
            maxLength={AGENT_GOAL_MAX_LENGTH}
            rows={4}
            placeholder="For example: get the login flow ready for the security review"
            disabled={startMutation.isPending || !available}
            onChange={(event) => setGoal(event.target.value)}
          />
        </label>
        <Button
          type="submit"
          disabled={!available || isLoadingStatus || startMutation.isPending || !goal.trim()}
        >
          <Bot aria-hidden="true" />
          {startMutation.isPending ? 'Planning' : 'Generate plan'}
        </Button>
      </form>
      {startMutation.isPending ? (
        <AgentWorking>
          The agent is searching this project&apos;s issues and drafting a plan. This usually takes 10 to 40 seconds.
        </AgentWorking>
      ) : null}
      {startMutation.data?.status === 'INSUFFICIENT_INFO' ? (
        <AgentMissingNotice missing={startMutation.data.missing ?? []}>
          The agent could not make a plan from this goal. Add what it was missing and try again:
        </AgentMissingNotice>
      ) : null}
      {startMutation.error ? <AgentErrorNotice error={startMutation.error} /> : null}
    </section>
  )
}

function RecentRuns({ workspaceId, projectId, openRunId, onOpenRun }: {
  workspaceId: string
  projectId: string
  openRunId: string | null
  onOpenRun: (runId: string) => void
}) {
  const runsQuery = useQuery({
    queryKey: agentQueryKeys.runs(workspaceId, projectId),
    queryFn: () => listAgentRuns(projectId),
    retry: false,
  })
  const runs: AgentRunSummary[] = runsQuery.data ?? []

  return (
    <section className="agent-card" aria-labelledby="agent-runs-title">
      <h2 id="agent-runs-title">Your recent runs</h2>
      {runsQuery.isLoading ? <InlineState>Loading runs.</InlineState> : null}
      {runsQuery.error ? <ErrorState error={runsQuery.error} /> : null}
      {runsQuery.isSuccess && runs.length === 0 ? <p className="agent-muted">No runs on this project yet.</p> : null}
      {runs.length > 0 ? (
        <ul className="agent-run-list">
          {runs.map((run) => (
            <li key={run.runId}>
              <button
                type="button"
                className="agent-run-link"
                data-active={run.runId === openRunId}
                aria-current={run.runId === openRunId ? 'true' : undefined}
                onClick={() => onOpenRun(run.runId)}
              >
                <strong>{run.goal}</strong>
                <span>
                  <AgentRunStateBadge state={run.state} />
                  <small>
                    {run.latestVersion === 1 ? '1 version' : `${run.latestVersion} versions`} · {formatDate(run.createdAt)}
                  </small>
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}
