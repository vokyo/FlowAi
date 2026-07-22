import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  dismissAiSuggestion,
  generateIssueSummary,
  getAiSuggestion,
  type AiSuggestion,
  type IssueSummaryContent,
} from '@/ai/ai-api'
import { IssueSummaryDrawer } from './IssueSummaryDrawer'

vi.mock('@/ai/ai-api', () => ({
  dismissAiSuggestion: vi.fn(),
  generateIssueSummary: vi.fn(),
  getAiSuggestion: vi.fn(),
}))

const suggestion: AiSuggestion<IssueSummaryContent> = {
  id: 'summary-1',
  type: 'ISSUE_SUMMARY',
  status: 'DRAFT',
  projectId: 'project-1',
  sourceIssueId: 'issue-1',
  content: {
    summary: 'The issue is ready for review.',
    decisions: ['Use the API contract.'],
    openQuestions: [],
    blockers: [],
    nextActions: ['Run integration tests.'],
    sourceStats: {
      commentsUsed: 2,
      activityEventsUsed: 1,
      commentsTruncated: true,
      activityTruncated: false,
      contextTruncated: true,
    },
  },
  metadata: {
    promptVersion: 'issue-summary-v1',
    generatedAt: '2026-07-21T01:00:00Z',
    contextTruncated: true,
  },
  createdAt: '2026-07-21T01:00:00Z',
  expiresAt: '2026-07-28T01:00:00Z',
}

describe('IssueSummaryDrawer', () => {
  beforeEach(() => {
    vi.mocked(generateIssueSummary).mockResolvedValue(suggestion)
    vi.mocked(getAiSuggestion).mockResolvedValue(suggestion)
    vi.mocked(dismissAiSuggestion).mockResolvedValue({ ...suggestion, status: 'DISMISSED' })
  })

  it('generates and restores a truncated read-only summary', async () => {
    renderDrawer()

    await userEvent.click(screen.getByRole('button', { name: 'Generate summary' }))

    expect(await screen.findByText('The issue is ready for review.')).toBeInTheDocument()
    expect(screen.getByText('Based on recent activity; older context was truncated.')).toBeInTheDocument()
    expect(generateIssueSummary).toHaveBeenCalledWith('issue-1', {
      includeComments: true,
      includeActivity: true,
    })
  })
})

function renderDrawer() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Harness() {
    const [suggestionId, setSuggestionId] = useState<string | null>(null)
    return (
      <IssueSummaryDrawer
        open
        workspaceId="workspace-1"
        issueId="issue-1"
        suggestionId={suggestionId}
        onSuggestionChange={setSuggestionId}
        onClose={vi.fn()}
      />
    )
  }
  return render(<QueryClientProvider client={queryClient}><Harness /></QueryClientProvider>)
}
