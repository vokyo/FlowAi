import type { AgentRunState } from '@/api/agent-api'

const LABELS: Record<AgentRunState, string> = {
  REVIEWING: 'Waiting for review',
  APPROVED: 'Approved',
  CANCELLED: 'Cancelled',
}

export function AgentRunStateBadge({ state }: { state: AgentRunState }) {
  return <span className="agent-state-badge" data-state={state}>{LABELS[state]}</span>
}
