import { ApiError } from '@/api/client'
import { ErrorState, InlineNotice } from '@/ui/feature-ui'

/**
 * Says what went wrong with a run in words that tell the user what to do next. A
 * refusal that the server explains itself (a closed run, a version that can no
 * longer be approved) shows the server's reason.
 */
export function AgentErrorNotice({ error }: { error: Error }) {
  if (error instanceof ApiError) {
    switch (readErrorCode(error.payload)) {
      case 'AI_AGENT_UNAVAILABLE':
        return <InlineNotice tone="warning">The planning agent is not reachable right now. Nothing was changed.</InlineNotice>
      case 'AI_AGENT_TIMEOUT':
        return <InlineNotice tone="warning">The planning agent took too long and was stopped. Try a narrower goal.</InlineNotice>
      case 'AI_AGENT_RUN_FAILED':
      case 'AI_AGENT_INVALID_RESPONSE':
        return <InlineNotice tone="warning">The planning agent could not produce a plan. Try again.</InlineNotice>
      case 'AI_AGENT_RUN_IN_PROGRESS':
        return <InlineNotice tone="warning">You already have a run going on this project. Wait for it to finish.</InlineNotice>
      case 'AI_RATE_LIMITED':
        return <InlineNotice tone="warning">AI limit reached. Wait briefly and retry.</InlineNotice>
      case 'AI_PLAN_VERSION_OUTDATED':
      case 'AI_PLAN_VERSION_CHANGED':
        return <InlineNotice tone="warning">A newer version replaced this one. Review the latest version.</InlineNotice>
      case 'AI_PLAN_VERSION_LIMIT':
        return <InlineNotice tone="warning">This run has used all of its versions. Approve or cancel it.</InlineNotice>
      case 'AI_PLAN_VERSION_NOT_APPROVABLE':
      case 'AI_AGENT_RUN_CLOSED':
      case 'AI_AGENT_RUN_NOT_FOUND':
        return <InlineNotice tone="warning">{error.message}</InlineNotice>
    }
  }
  return <ErrorState error={error} />
}

function readErrorCode(payload: unknown) {
  if (!payload || typeof payload !== 'object' || !('code' in payload)) return null
  return typeof payload.code === 'string' ? payload.code : null
}
