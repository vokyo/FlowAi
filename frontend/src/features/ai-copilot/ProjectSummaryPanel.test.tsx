import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  dismissAiSuggestion,
  generateProjectSummary,
  getAiSuggestion,
  type AiSuggestion,
  type ProjectSummaryContent,
} from '@/ai/ai-api'
import { ProjectSummaryPanel } from './ProjectSummaryPanel'

vi.mock('@/ai/ai-api', () => ({
  dismissAiSuggestion: vi.fn(),
  generateProjectSummary: vi.fn(),
  getAiSuggestion: vi.fn(),
}))

const suggestion: AiSuggestion<ProjectSummaryContent> = {
  id: 'project-summary-1',
  type: 'PROJECT_SUMMARY',
  status: 'DRAFT',
  projectId: 'project-1',
  content: {
    executiveSummary: 'Delivery is progressing.',
    progressHighlights: ['Core work is active.'],
    currentRisks: [],
    blockers: [],
    workloadObservations: ['Two issues are unassigned.'],
    recommendedNextActions: ['Assign the urgent work.'],
    sourceStats: {
      activeIssuesUsed: 100,
      totalActiveIssues: 120,
      rangeDays: 30,
      contextTruncated: true,
    },
  },
  metadata: {
    promptVersion: 'project-summary-v1',
    generatedAt: '2026-07-21T01:00:00Z',
    contextTruncated: true,
  },
  createdAt: '2026-07-21T01:00:00Z',
  expiresAt: '2026-07-28T01:00:00Z',
}

describe('ProjectSummaryPanel', () => {
  beforeEach(() => {
    vi.mocked(generateProjectSummary).mockResolvedValue(suggestion)
    vi.mocked(getAiSuggestion).mockResolvedValue(suggestion)
    vi.mocked(dismissAiSuggestion).mockResolvedValue({ ...suggestion, status: 'DISMISSED' })
  })

  it('generates a range-aware project summary and shows truncation', async () => {
    renderPanel()
    await userEvent.type(screen.getByLabelText('Optional focus'), 'Delivery risk')
    await userEvent.click(screen.getByRole('button', { name: 'Generate 30-day summary' }))

    expect(await screen.findByText('Delivery is progressing.')).toBeInTheDocument()
    expect(screen.getByText('Based on the highest-priority 100 of 120 active issues.')).toBeInTheDocument()
    expect(generateProjectSummary).toHaveBeenCalledWith('project-1', {
      rangeDays: 30,
      focus: 'Delivery risk',
    })
  })
})

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Harness() {
    const [suggestionId, setSuggestionId] = useState<string | null>(null)
    return (
      <ProjectSummaryPanel
        workspaceId="workspace-1"
        projectId="project-1"
        rangeDays={30}
        suggestionId={suggestionId}
        available
        isLoadingStatus={false}
        onSuggestionChange={setSuggestionId}
      />
    )
  }
  return render(<QueryClientProvider client={queryClient}><Harness /></QueryClientProvider>)
}
