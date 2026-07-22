import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Clipboard, FileText, Loader2, RefreshCw, X } from 'lucide-react'
import {
  dismissAiSuggestion,
  generateIssueSummary,
  getAiSuggestion,
  type AiSuggestion,
  type IssueSummaryContent,
} from '@/ai/ai-api'
import { Button } from '@/components/ui/button'
import { InlineNotice, InlineState } from '@/features/project-shell/feature-ui'
import { AiErrorNotice } from './AiErrorNotice'

type IssueSummaryDrawerProps = {
  open: boolean
  workspaceId: string | null
  issueId: string
  suggestionId: string | null
  onSuggestionChange: (suggestionId: string) => void
  onClose: () => void
}

export function IssueSummaryDrawer({
  open,
  workspaceId,
  issueId,
  suggestionId,
  onSuggestionChange,
  onClose,
}: IssueSummaryDrawerProps) {
  const queryClient = useQueryClient()
  const [includeComments, setIncludeComments] = useState(true)
  const [includeActivity, setIncludeActivity] = useState(true)
  const [copied, setCopied] = useState(false)

  const suggestionQuery = useQuery({
    queryKey: ['ai-suggestion', workspaceId, suggestionId],
    queryFn: () => getAiSuggestion<IssueSummaryContent>(suggestionId ?? ''),
    enabled: Boolean(open && suggestionId),
    retry: false,
  })

  const generateMutation = useMutation({
    mutationFn: () => generateIssueSummary(issueId, {
      includeComments,
      includeActivity,
    }),
    onSuccess: (suggestion) => {
      setCopied(false)
      queryClient.setQueryData(
        ['ai-suggestion', workspaceId, suggestion.id],
        suggestion,
      )
      onSuggestionChange(suggestion.id)
    },
  })

  const dismissMutation = useMutation({
    mutationFn: () => dismissAiSuggestion(suggestionId ?? ''),
    onSuccess: (suggestion) => {
      queryClient.setQueryData(
        ['ai-suggestion', workspaceId, suggestion.id],
        suggestion,
      )
      onClose()
    },
  })

  const suggestion = suggestionQuery.data

  async function copySummary(content: IssueSummaryContent) {
    await navigator.clipboard.writeText(summaryText(content))
    setCopied(true)
  }

  if (!open) return null

  return (
    <div className="ai-drawer-layer" role="presentation">
      <button
        className="ai-drawer-backdrop"
        type="button"
        aria-label="Close AI Copilot"
        onClick={onClose}
      />
      <aside
        className="ai-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ai-summary-title"
      >
        <header className="ai-drawer-header">
          <div>
            <span className="ai-drawer-eyebrow"><FileText aria-hidden="true" /> AI Copilot</span>
            <h2 id="ai-summary-title">Issue summary</h2>
          </div>
          <Button type="button" variant="ghost" size="icon" aria-label="Close" onClick={onClose}>
            <X aria-hidden="true" />
          </Button>
        </header>

        <div className="ai-drawer-body">
          {!suggestionId ? (
            <form
              className="ai-request-form"
              onSubmit={(event) => {
                event.preventDefault()
                generateMutation.mutate()
              }}
            >
              <p>Summaries are read-only and use only issue data you can access.</p>
              <label className="ai-check-row">
                <input
                  type="checkbox"
                  checked={includeComments}
                  disabled={generateMutation.isPending}
                  onChange={(event) => setIncludeComments(event.target.checked)}
                />
                Include recent comments
              </label>
              <label className="ai-check-row">
                <input
                  type="checkbox"
                  checked={includeActivity}
                  disabled={generateMutation.isPending}
                  onChange={(event) => setIncludeActivity(event.target.checked)}
                />
                Include recent activity
              </label>
              {generateMutation.error ? <AiErrorNotice error={generateMutation.error} /> : null}
              <Button type="submit" disabled={generateMutation.isPending}>
                {generateMutation.isPending ? <Loader2 className="auth-spin" aria-hidden="true" /> : <FileText aria-hidden="true" />}
                {generateMutation.isPending ? 'Summarizing' : 'Generate summary'}
              </Button>
            </form>
          ) : null}

          {suggestionId && suggestionQuery.isLoading ? (
            <InlineState>Loading saved AI summary.</InlineState>
          ) : null}
          {suggestionQuery.error ? <AiErrorNotice error={suggestionQuery.error} /> : null}
          {suggestion && suggestion.type !== 'ISSUE_SUMMARY' ? (
            <InlineNotice tone="warning">This suggestion is not an issue summary.</InlineNotice>
          ) : null}

          {suggestion?.type === 'ISSUE_SUMMARY' ? (
            <>
              {suggestion.status === 'EXPIRED' || suggestion.status === 'DISMISSED' ? (
                <InlineNotice tone="warning">
                  This summary is {suggestion.status.toLowerCase()}. Generate a fresh summary.
                </InlineNotice>
              ) : null}
              <IssueSummaryContentView suggestion={suggestion} />
              {generateMutation.error ? <AiErrorNotice error={generateMutation.error} /> : null}
              {dismissMutation.error ? <AiErrorNotice error={dismissMutation.error} /> : null}
              <footer className="ai-drawer-actions ai-summary-actions">
                <Button
                  type="button"
                  variant="ghost"
                  disabled={dismissMutation.isPending || suggestion.status !== 'DRAFT'}
                  onClick={() => dismissMutation.mutate()}
                >
                  Dismiss
                </Button>
                <div>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => void copySummary(suggestion.content)}
                  >
                    {copied ? <Check aria-hidden="true" /> : <Clipboard aria-hidden="true" />}
                    {copied ? 'Copied' : 'Copy'}
                  </Button>
                  <Button
                    type="button"
                    disabled={generateMutation.isPending}
                    onClick={() => generateMutation.mutate()}
                  >
                    {generateMutation.isPending ? <Loader2 className="auth-spin" aria-hidden="true" /> : <RefreshCw aria-hidden="true" />}
                    Refresh
                  </Button>
                </div>
              </footer>
            </>
          ) : null}
        </div>
      </aside>
    </div>
  )
}

function IssueSummaryContentView({
  suggestion,
}: {
  suggestion: AiSuggestion<IssueSummaryContent>
}) {
  const content = suggestion.content
  return (
    <article className="ai-summary-content">
      <section className="ai-summary-lead">
        <span>Generated {new Date(suggestion.createdAt).toLocaleString()}</span>
        <p>{content.summary}</p>
      </section>
      {suggestion.metadata.contextTruncated || content.sourceStats.contextTruncated ? (
        <InlineNotice tone="warning">Based on recent activity; older context was truncated.</InlineNotice>
      ) : null}
      <SummaryList title="Decisions" values={content.decisions} empty="No explicit decisions found." />
      <SummaryList title="Open questions" values={content.openQuestions} empty="No open questions found." />
      <SummaryList title="Blockers" values={content.blockers} empty="No current blockers found." />
      <SummaryList title="Next actions" values={content.nextActions} empty="No next actions suggested." />
      <small className="ai-source-stats">
        Used {content.sourceStats.commentsUsed} comments and {content.sourceStats.activityEventsUsed} activity events.
      </small>
    </article>
  )
}

function SummaryList({ title, values, empty }: { title: string; values: string[]; empty: string }) {
  return (
    <section className="ai-summary-section">
      <h3>{title}</h3>
      {values.length > 0 ? (
        <ul>{values.map((value) => <li key={value}>{value}</li>)}</ul>
      ) : (
        <p>{empty}</p>
      )}
    </section>
  )
}

function summaryText(content: IssueSummaryContent) {
  const sections = [
    content.summary,
    listText('Decisions', content.decisions),
    listText('Open questions', content.openQuestions),
    listText('Blockers', content.blockers),
    listText('Next actions', content.nextActions),
  ].filter(Boolean)
  return sections.join('\n\n')
}

function listText(title: string, values: string[]) {
  return values.length > 0 ? `${title}:\n- ${values.join('\n- ')}` : ''
}
