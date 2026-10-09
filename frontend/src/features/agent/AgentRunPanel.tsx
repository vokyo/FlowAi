import { useState } from 'react'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Check, Loader2, RefreshCw, X } from 'lucide-react'
import {
  AGENT_FEEDBACK_MAX_LENGTH,
  AGENT_MAX_VERSIONS,
  approveAgentRun,
  cancelAgentRun,
  getAgentRun,
  reviseAgentRun,
  type AgentRunStats,
} from '@/api/agent-api'
import { getIssue } from '@/api/work-api'
import { Button } from '@/components/ui/button'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import { issuePath } from '@/routing/route-utils'
import { formatDate, formatDateOnly } from '@/ui/display-utils'
import { InlineNotice, InlineState } from '@/ui/feature-ui'
import { AgentErrorNotice } from './AgentErrorNotice'
import { AgentMissingNotice } from './AgentMissingNotice'
import { AgentPlanView } from './AgentPlanView'
import { AgentRunStateBadge } from './AgentRunStateBadge'
import { AgentWorking } from './AgentWorking'
import { agentQueryKeys } from './agent-query-keys'

type AgentRunPanelProps = {
  workspaceId: string
  projectId: string
  runId: string
  available: boolean
  onClose: () => void
}

/**
 * One run and its plan. Only the latest version of a run under review can be
 * revised or approved; older versions stay readable. Every action re-reads the run
 * afterwards, because a refusal (a newer version, a run cancelled elsewhere, a plan
 * that no longer passes the checks) means the run changed on the server.
 */
export function AgentRunPanel({ workspaceId, projectId, runId, available, onClose }: AgentRunPanelProps) {
  const queryClient = useQueryClient()
  const [chosenVersion, setChosenVersion] = useState<number | null>(null)
  const [feedback, setFeedback] = useState('')
  const runQuery = useQuery({
    queryKey: agentQueryKeys.run(workspaceId, runId),
    queryFn: () => getAgentRun(runId),
    retry: false,
  })

  function refreshRun() {
    return Promise.all([
      queryClient.invalidateQueries({ queryKey: agentQueryKeys.run(workspaceId, runId) }),
      queryClient.invalidateQueries({ queryKey: agentQueryKeys.runs(workspaceId, projectId) }),
    ])
  }

  const reviseMutation = useMutation({
    mutationFn: (request: { basedOnVersion: number; feedback: string }) => reviseAgentRun(runId, request),
    onSuccess: async (response) => {
      if (response.status === 'PLANNED' && response.version) {
        queryClient.setQueryData(agentQueryKeys.stats(workspaceId, runId, response.version), response.stats ?? null)
        setFeedback('')
        setChosenVersion(null)
      }
      await refreshRun()
    },
    onError: () => refreshRun(),
  })
  const approveMutation = useMutation({
    mutationFn: (request: { version: number; contentHash: string }) => approveAgentRun(runId, request),
    onSuccess: () => Promise.all([
      refreshRun(),
      resetProjectBoard(queryClient, workspaceId, projectId),
      queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, projectId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.analytics(workspaceId, projectId) }),
    ]),
    onError: () => refreshRun(),
  })
  const cancelMutation = useMutation({
    mutationFn: () => cancelAgentRun(runId),
    onSuccess: async (detail) => {
      queryClient.setQueryData(agentQueryKeys.run(workspaceId, runId), detail)
      await queryClient.invalidateQueries({ queryKey: agentQueryKeys.runs(workspaceId, projectId) })
    },
    onError: () => refreshRun(),
  })

  const run = runQuery.data
  if (runQuery.isLoading) {
    return <section className="agent-panel"><InlineState>Loading the run.</InlineState></section>
  }
  if (runQuery.error || !run) {
    return (
      <section className="agent-panel">
        {runQuery.error ? <AgentErrorNotice error={runQuery.error} /> : null}
        <div><Button type="button" variant="outline" onClick={onClose}>Back to runs</Button></div>
      </section>
    )
  }

  const shown = run.versions.find((version) => version.version === (chosenVersion ?? run.latestVersion))
    ?? run.versions[run.versions.length - 1]
  const isLatest = shown.version === run.latestVersion
  const canReview = run.state === 'REVIEWING' && isLatest
  const busy = reviseMutation.isPending || approveMutation.isPending || cancelMutation.isPending
  const atVersionLimit = run.latestVersion >= AGENT_MAX_VERSIONS
  const stats = queryClient.getQueryData<AgentRunStats | null>(
    agentQueryKeys.stats(workspaceId, runId, shown.version),
  )
  const newIssueCount = shown.plan.items.length

  return (
    <section className="agent-panel" aria-labelledby="agent-run-title">
      <header className="agent-panel-header">
        <div>
          <AgentRunStateBadge state={run.state} />
          <h2 id="agent-run-title">{run.goal}</h2>
          <p>Started {formatDate(run.createdAt)} · dates planned from {formatDateOnly(run.generatedOn)}</p>
        </div>
        <Button type="button" variant="ghost" size="icon" aria-label="Close run" onClick={onClose}>
          <X aria-hidden="true" />
        </Button>
      </header>

      {run.versions.length > 1 ? (
        <div className="agent-version-switch" role="group" aria-label="Plan versions">
          {run.versions.map((version) => (
            <Button
              key={version.version}
              type="button"
              size="sm"
              variant={version.version === shown.version ? 'secondary' : 'ghost'}
              aria-pressed={version.version === shown.version}
              onClick={() => setChosenVersion(version.version === run.latestVersion ? null : version.version)}
            >
              Version {version.version}
            </Button>
          ))}
        </div>
      ) : null}

      {!isLatest ? (
        <InlineNotice>
          Version {shown.version} was replaced by version {run.latestVersion}.
          {run.state === 'REVIEWING' ? ' Only the latest version can be revised or approved.' : ''}
        </InlineNotice>
      ) : null}
      {run.state === 'APPROVED' && isLatest ? (
        <CreatedIssues workspaceId={workspaceId} projectId={projectId} issueIds={shown.createdIssueIds} />
      ) : null}
      {run.state === 'CANCELLED' ? (
        <InlineNotice>This run was cancelled. Nothing from its plan was created.</InlineNotice>
      ) : null}
      {canReview && !shown.approvable ? (
        <InlineNotice tone="warning">
          This version cannot be approved: {shown.rejectionReason ?? 'it no longer passes the checks'}. Say what
          should change and revise it, or cancel the run.
        </InlineNotice>
      ) : null}
      {stats?.toolCalls != null && stats.decisionRounds != null ? (
        <p className="agent-muted">
          The agent made {stats.toolCalls} {stats.toolCalls === 1 ? 'lookup' : 'lookups'} over{' '}
          {stats.decisionRounds} {stats.decisionRounds === 1 ? 'round' : 'rounds'} for this version.
        </p>
      ) : null}

      <AgentPlanView plan={shown.plan} workspaceId={workspaceId} projectId={projectId} />

      {canReview ? (
        <footer className="agent-review">
          <form
            className="agent-revise-form"
            onSubmit={(event) => {
              event.preventDefault()
              reviseMutation.mutate({ basedOnVersion: shown.version, feedback: feedback.trim() })
            }}
          >
            <label>
              What should change?
              <textarea
                value={feedback}
                maxLength={AGENT_FEEDBACK_MAX_LENGTH}
                rows={3}
                placeholder="For example: split the API work into two issues and drop the documentation task"
                disabled={busy || !available || atVersionLimit}
                onChange={(event) => setFeedback(event.target.value)}
              />
            </label>
            {atVersionLimit ? (
              <p className="agent-muted">This run has reached its {AGENT_MAX_VERSIONS} versions. Approve it or cancel it.</p>
            ) : null}
            {!available ? (
              <p className="agent-muted">Revising needs the planning agent, which is not running on this deployment.</p>
            ) : null}
            <Button
              type="submit"
              variant="outline"
              disabled={busy || !available || atVersionLimit || !feedback.trim()}
            >
              <RefreshCw aria-hidden="true" />
              Revise plan
            </Button>
          </form>
          {reviseMutation.isPending ? (
            <AgentWorking>The agent is revising version {shown.version} with your feedback.</AgentWorking>
          ) : null}
          {reviseMutation.data?.status === 'INSUFFICIENT_INFO' ? (
            <AgentMissingNotice missing={reviseMutation.data.missing ?? []}>
              The agent could not revise the plan, so version {run.latestVersion} is unchanged. It was missing:
            </AgentMissingNotice>
          ) : null}
          {reviseMutation.error ? <AgentErrorNotice error={reviseMutation.error} /> : null}

          <div className="agent-review-actions">
            <Button
              type="button"
              variant="ghost"
              disabled={busy}
              onClick={() => {
                if (window.confirm('Cancel this run? Nothing from its plan will be created.')) cancelMutation.mutate()
              }}
            >
              Cancel run
            </Button>
            <Button
              type="button"
              disabled={busy || !shown.approvable}
              onClick={() => approveMutation.mutate({ version: shown.version, contentHash: shown.contentHash })}
            >
              {approveMutation.isPending ? <Loader2 className="auth-spin" aria-hidden="true" /> : <Check aria-hidden="true" />}
              {newIssueCount === 1 ? 'Approve and create 1 issue' : `Approve and create ${newIssueCount} issues`}
            </Button>
          </div>
          {approveMutation.error ? <AgentErrorNotice error={approveMutation.error} /> : null}
          {cancelMutation.error ? <AgentErrorNotice error={cancelMutation.error} /> : null}
        </footer>
      ) : null}
    </section>
  )
}

function CreatedIssues({ workspaceId, projectId, issueIds }: {
  workspaceId: string
  projectId: string
  issueIds: string[]
}) {
  const issueQueries = useQueries({
    queries: issueIds.map((issueId) => ({
      queryKey: queryKeys.issue(workspaceId, issueId),
      queryFn: () => getIssue(issueId),
      retry: false,
    })),
  })

  return (
    <div className="agent-notice" data-tone="success" role="status">
      <p>
        {issueIds.length === 0
          ? 'Approved. The plan reused existing issues and created none.'
          : `Approved. ${issueIds.length === 1 ? '1 issue was' : `${issueIds.length} issues were`} created:`}
      </p>
      {issueIds.length > 0 ? (
        <ul>
          {issueIds.map((issueId, index) => (
            <li key={issueId}>
              <Link to={issuePath(workspaceId, projectId, issueId)}>
                {issueQueries[index]?.data?.title ?? 'Open the new issue'}
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
