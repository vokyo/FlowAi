import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  applyIssueBreakdown,
  dismissAiSuggestion,
  generateIssueBreakdown,
  getAiSuggestion,
  type AiSuggestion,
  type IssueBreakdownContent,
} from '@/ai/ai-api'
import { IssueCopilotDrawer } from './IssueCopilotDrawer'

vi.mock('@/ai/ai-api', () => ({
  applyIssueBreakdown: vi.fn(),
  dismissAiSuggestion: vi.fn(),
  generateIssueBreakdown: vi.fn(),
  getAiSuggestion: vi.fn(),
}))

const suggestion: AiSuggestion<IssueBreakdownContent> = {
  id: 'suggestion-1',
  type: 'ISSUE_BREAKDOWN',
  status: 'DRAFT',
  projectId: 'project-1',
  sourceIssueId: 'issue-1',
  content: {
    overview: 'Two tasks',
    warnings: [],
    items: [
      {
        clientItemId: 'item-1',
        title: 'Backend task',
        description: 'Backend description',
        priority: 'HIGH',
        acceptanceCriteria: ['API works'],
        suggestedLabelIds: [],
        suggestedAssigneeUserId: null,
        dueDate: null,
        dependsOnClientItemIds: [],
      },
      {
        clientItemId: 'item-2',
        title: 'Test task',
        description: 'Test description',
        priority: 'MEDIUM',
        acceptanceCriteria: [],
        suggestedLabelIds: [],
        suggestedAssigneeUserId: null,
        dueDate: null,
        dependsOnClientItemIds: ['item-1'],
      },
    ],
  },
  metadata: {
    promptVersion: 'issue-breakdown-v1',
    generatedAt: '2026-07-21T01:00:00Z',
    contextTruncated: false,
  },
  createdIssueIds: [],
  createdAt: '2026-07-21T01:00:00Z',
  expiresAt: '2026-07-28T01:00:00Z',
}

describe('IssueCopilotDrawer', () => {
  beforeEach(() => {
    vi.mocked(generateIssueBreakdown).mockResolvedValue(suggestion)
    vi.mocked(getAiSuggestion).mockResolvedValue(suggestion)
    vi.mocked(dismissAiSuggestion).mockResolvedValue({
      ...suggestion,
      status: 'DISMISSED',
    })
    vi.stubGlobal('crypto', { randomUUID: () => 'idempotency-key' })
  })

  it('keeps edited draft fields when Apply fails', async () => {
    vi.mocked(applyIssueBreakdown).mockRejectedValue(new Error('Apply failed'))
    renderDrawer()

    await userEvent.click(screen.getByRole('button', { name: 'Generate breakdown' }))
    const title = await screen.findByDisplayValue('Backend task')
    await userEvent.clear(title)
    await userEvent.type(title, 'Edited backend task')
    await userEvent.click(screen.getByRole('button', { name: 'Create 2 issues' }))

    expect(await screen.findByText('Unable to load this workspace data.')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Edited backend task')).toBeInTheDocument()
    expect(applyIssueBreakdown).toHaveBeenCalledWith(
      'suggestion-1',
      expect.objectContaining({ idempotencyKey: 'idempotency-key' }),
    )
  })

  it('allows deselecting an item and submits the complete item set', async () => {
    vi.mocked(applyIssueBreakdown).mockResolvedValue({
      suggestionId: suggestion.id,
      status: 'APPLIED',
      createdIssueIds: ['created-1'],
      appliedAt: '2026-07-21T02:00:00Z',
    })
    renderDrawer()

    await userEvent.click(screen.getByRole('button', { name: 'Generate breakdown' }))
    const taskSelectors = await screen.findAllByRole('checkbox', { name: /Task/ })
    await userEvent.click(taskSelectors[1])
    await userEvent.click(screen.getByRole('button', { name: 'Create 1 issue' }))

    await waitFor(() => expect(applyIssueBreakdown).toHaveBeenCalled())
    const request = vi.mocked(applyIssueBreakdown).mock.calls[0][1]
    expect(request.items).toHaveLength(2)
    expect(request.items[0].selected).toBe(true)
    expect(request.items[1].selected).toBe(false)
    expect(await screen.findByText('1 issue created')).toBeInTheDocument()
  })

  it('ignores a cached summary suggestion while the breakdown drawer is closed', () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    queryClient.setQueryData(
      ['ai-suggestion', 'workspace-1', 'summary-1'],
      {
        ...suggestion,
        id: 'summary-1',
        type: 'ISSUE_SUMMARY',
        content: {
          summary: 'A valid issue summary.',
          decisions: [],
          openQuestions: [],
          blockers: [],
          nextActions: [],
          sourceStats: {
            commentsUsed: 0,
            activityEventsUsed: 0,
            commentsTruncated: false,
            activityTruncated: false,
            contextTruncated: false,
          },
        },
      },
    )

    render(
      <QueryClientProvider client={queryClient}>
        <IssueCopilotDrawer
          open={false}
          workspaceId="workspace-1"
          issueId="issue-1"
          suggestionId="summary-1"
          projectMembers={[]}
          projectLabels={[]}
          workflowStates={[]}
          onSuggestionChange={vi.fn()}
          onClose={vi.fn()}
          onApplied={vi.fn(async () => undefined)}
        />
      </QueryClientProvider>,
    )

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(getAiSuggestion).not.toHaveBeenCalled()
  })
})

function renderDrawer() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  function Harness() {
    const [suggestionId, setSuggestionId] = useState<string | null>(null)
    return (
      <IssueCopilotDrawer
        open
        workspaceId="workspace-1"
        issueId="issue-1"
        suggestionId={suggestionId}
        projectMembers={[]}
        projectLabels={[]}
        workflowStates={[]}
        onSuggestionChange={setSuggestionId}
        onClose={vi.fn()}
        onApplied={vi.fn(async () => undefined)}
      />
    )
  }

  return render(
    <QueryClientProvider client={queryClient}>
      <Harness />
    </QueryClientProvider>,
  )
}
