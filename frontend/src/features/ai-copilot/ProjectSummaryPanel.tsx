import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Clipboard, FileText, Loader2, RefreshCw, X } from 'lucide-react'
import {
  dismissAiSuggestion,
  generateProjectSummary,
  getAiSuggestion,
  type AiSuggestion,
  type ProjectSummaryContent,
} from '@/ai/ai-api'
import type { AnalyticsRangeDays } from '@/analytics/analytics-api'
import { Button } from '@/components/ui/button'
import { InlineNotice, InlineState } from '@/features/project-shell/feature-ui'
import { AiErrorNotice } from './AiErrorNotice'

type ProjectSummaryPanelProps = {
  workspaceId: string | null
  projectId: string
  rangeDays: AnalyticsRangeDays
  suggestionId: string | null
  available: boolean
  isLoadingStatus: boolean
  onSuggestionChange: (suggestionId: string | null) => void
}

export function ProjectSummaryPanel({
  workspaceId,
  projectId,
  rangeDays,
  suggestionId,
  available,
  isLoadingStatus,
  onSuggestionChange,
}: ProjectSummaryPanelProps) {
  const queryClient = useQueryClient()
  const [focus, setFocus] = useState('')
  const [copied, setCopied] = useState(false)
  const suggestionQuery = useQuery({
    queryKey: ['ai-suggestion', workspaceId, suggestionId],
    queryFn: () => getAiSuggestion<ProjectSummaryContent>(suggestionId ?? ''),
    enabled: Boolean(suggestionId),
    retry: false,
  })
  const generateMutation = useMutation({
    mutationFn: () => generateProjectSummary(projectId, {
      rangeDays,
      focus: focus.trim() || undefined,
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
    onSuccess: () => onSuggestionChange(null),
  })
  const suggestion = suggestionQuery.data

  async function copy(content: ProjectSummaryContent) {
    await navigator.clipboard.writeText(projectSummaryText(content))
    setCopied(true)
  }

  return (
    <section className="analytics-panel ai-project-summary-panel" aria-labelledby="project-ai-summary-title">
      <header className="ai-project-summary-header">
        <div>
          <span className="ai-drawer-eyebrow"><FileText aria-hidden="true" /> AI Copilot</span>
          <h2 id="project-ai-summary-title">Project summary</h2>
          <p>Read-only AI draft based on the selected analytics range.</p>
        </div>
        {suggestionId ? (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="Close project summary"
            onClick={() => onSuggestionChange(null)}
          >
            <X aria-hidden="true" />
          </Button>
        ) : null}
      </header>

      {!suggestionId ? (
        <form
          className="ai-project-summary-form"
          onSubmit={(event) => {
            event.preventDefault()
            generateMutation.mutate()
          }}
        >
          <label>
            Optional focus
            <textarea
              value={focus}
              maxLength={2000}
              rows={2}
              placeholder="For example: delivery risk or workload balance"
              disabled={generateMutation.isPending}
              onChange={(event) => setFocus(event.target.value)}
            />
          </label>
          {generateMutation.error ? <AiErrorNotice error={generateMutation.error} /> : null}
          <Button
            type="submit"
            disabled={!available || isLoadingStatus || generateMutation.isPending}
            title={available ? undefined : 'AI Copilot is unavailable'}
          >
            {generateMutation.isPending || isLoadingStatus
              ? <Loader2 className="auth-spin" aria-hidden="true" />
              : <FileText aria-hidden="true" />}
            {generateMutation.isPending ? 'Generating' : `Generate ${rangeDays}-day summary`}
          </Button>
        </form>
      ) : null}

      {suggestionId && suggestionQuery.isLoading ? <InlineState>Loading saved AI summary.</InlineState> : null}
      {suggestionQuery.error ? <AiErrorNotice error={suggestionQuery.error} /> : null}
      {suggestion && suggestion.type !== 'PROJECT_SUMMARY' ? (
        <InlineNotice tone="warning">This suggestion is not a project summary.</InlineNotice>
      ) : null}
      {suggestion?.type === 'PROJECT_SUMMARY' ? (
        <ProjectSummaryContentView suggestion={suggestion} />
      ) : null}
      {suggestion?.type === 'PROJECT_SUMMARY' ? (
        <footer className="ai-project-summary-actions">
          <Button
            type="button"
            variant="ghost"
            disabled={dismissMutation.isPending || suggestion.status !== 'DRAFT'}
            onClick={() => dismissMutation.mutate()}
          >
            Dismiss
          </Button>
          <div>
            <Button type="button" variant="outline" onClick={() => void copy(suggestion.content)}>
              {copied ? <Check aria-hidden="true" /> : <Clipboard aria-hidden="true" />}
              {copied ? 'Copied' : 'Copy'}
            </Button>
            <Button
              type="button"
              disabled={!available || generateMutation.isPending}
              onClick={() => generateMutation.mutate()}
            >
              {generateMutation.isPending
                ? <Loader2 className="auth-spin" aria-hidden="true" />
                : <RefreshCw aria-hidden="true" />}
              Refresh
            </Button>
          </div>
        </footer>
      ) : null}
      {dismissMutation.error ? <AiErrorNotice error={dismissMutation.error} /> : null}
      {generateMutation.error && suggestionId ? <AiErrorNotice error={generateMutation.error} /> : null}
    </section>
  )
}

function ProjectSummaryContentView({ suggestion }: { suggestion: AiSuggestion<ProjectSummaryContent> }) {
  const content = suggestion.content
  return (
    <article className="ai-summary-content">
      {suggestion.status !== 'DRAFT' ? (
        <InlineNotice tone="warning">
          This summary is {suggestion.status.toLowerCase()}. Refresh to create a new draft.
        </InlineNotice>
      ) : null}
      <section className="ai-summary-lead">
        <span>Generated {new Date(suggestion.createdAt).toLocaleString()}</span>
        <p>{content.executiveSummary}</p>
      </section>
      {suggestion.metadata.contextTruncated || content.sourceStats.contextTruncated ? (
        <InlineNotice tone="warning">Based on the highest-priority {content.sourceStats.activeIssuesUsed} of {content.sourceStats.totalActiveIssues} active issues.</InlineNotice>
      ) : null}
      <SummaryList title="Progress highlights" values={content.progressHighlights} />
      <SummaryList title="Current risks" values={content.currentRisks} />
      <SummaryList title="Blockers" values={content.blockers} />
      <SummaryList title="Workload observations" values={content.workloadObservations} />
      <SummaryList title="Recommended next actions" values={content.recommendedNextActions} />
      <small className="ai-source-stats">
        Used {content.sourceStats.activeIssuesUsed} active issues over {content.sourceStats.rangeDays} days.
      </small>
    </article>
  )
}

function SummaryList({ title, values }: { title: string; values: string[] }) {
  return (
    <section className="ai-summary-section">
      <h3>{title}</h3>
      {values.length > 0 ? <ul>{values.map((value) => <li key={value}>{value}</li>)}</ul> : <p>Nothing supported by the current context.</p>}
    </section>
  )
}

function projectSummaryText(content: ProjectSummaryContent) {
  const sections = [
    content.executiveSummary,
    listText('Progress highlights', content.progressHighlights),
    listText('Current risks', content.currentRisks),
    listText('Blockers', content.blockers),
    listText('Workload observations', content.workloadObservations),
    listText('Recommended next actions', content.recommendedNextActions),
  ].filter(Boolean)
  return sections.join('\n\n')
}

function listText(title: string, values: string[]) {
  return values.length > 0 ? `${title}:\n- ${values.join('\n- ')}` : ''
}
