import { ApiError } from '@/api/client'
import { ErrorState, InlineNotice } from '@/features/project-shell/feature-ui'

export function AiErrorNotice({ error }: { error: Error }) {
  if (error instanceof ApiError) {
    const code = readErrorCode(error.payload)
    if (code === 'AI_RATE_LIMITED') {
      return <InlineNotice tone="warning">AI limit reached. Wait briefly and retry.</InlineNotice>
    }
    if (code === 'AI_PROVIDER_TIMEOUT') {
      return <InlineNotice tone="warning">The AI provider timed out. Your draft is unchanged.</InlineNotice>
    }
    if (code === 'AI_PROVIDER_UNAVAILABLE') {
      return <InlineNotice tone="warning">AI is currently unavailable.</InlineNotice>
    }
    if (code === 'AI_INVALID_RESPONSE') {
      return <InlineNotice tone="warning">AI returned an invalid response. Please retry.</InlineNotice>
    }
  }
  return <ErrorState error={error} />
}

function readErrorCode(payload: unknown) {
  if (!payload || typeof payload !== 'object' || !('code' in payload)) return null
  return typeof payload.code === 'string' ? payload.code : null
}
