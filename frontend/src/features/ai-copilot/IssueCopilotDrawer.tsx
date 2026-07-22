import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Loader2, Sparkles, X } from 'lucide-react'
import {
  applyIssueBreakdown,
  dismissAiSuggestion,
  generateIssueBreakdown,
  getAiSuggestion,
  type AiSuggestion,
  type EditableBreakdownItem,
  type IssueBreakdownContent,
  type IssueBreakdownRequest,
} from '@/ai/ai-api'
import { Button } from '@/components/ui/button'
import { InlineNotice, InlineState } from '@/features/project-shell/feature-ui'
import { ISSUE_PRIORITIES, PRIORITY_LABELS } from '@/features/project-shell/project-model'
import type {
  IssuePriority,
  ProjectLabel,
  ProjectMember,
  ProjectWorkflowState,
} from '@/work/work-api'
import { AiErrorNotice } from './AiErrorNotice'

type IssueCopilotDrawerProps = {
  open: boolean
  workspaceId: string | null
  issueId: string
  suggestionId: string | null
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  workflowStates: ProjectWorkflowState[]
  onSuggestionChange: (suggestionId: string | null) => void
  onClose: () => void
  onApplied: (createdIssueIds: string[]) => Promise<void>
}

const DEFAULT_REQUEST: Required<IssueBreakdownRequest> = {
  instruction: '',
  maxItems: 5,
  includeComments: true,
  includeActivity: false,
}

export function IssueCopilotDrawer({
  open,
  workspaceId,
  issueId,
  suggestionId,
  projectMembers,
  projectLabels,
  workflowStates,
  onSuggestionChange,
  onClose,
  onApplied,
}: IssueCopilotDrawerProps) {
  const queryClient = useQueryClient()
  const [request, setRequest] = useState(DEFAULT_REQUEST)
  const [draftItems, setDraftItems] = useState<EditableBreakdownItem[]>([])
  const [appliedIssueIds, setAppliedIssueIds] = useState<string[]>([])
  const loadedSuggestionId = useRef<string | null>(null)
  const idempotencyKey = useRef<string | null>(null)

  const suggestionQuery = useQuery({
    queryKey: ['ai-suggestion', workspaceId, suggestionId],
    queryFn: () => getAiSuggestion<IssueBreakdownContent>(suggestionId ?? ''),
    enabled: Boolean(open && suggestionId),
    retry: false,
  })

  useEffect(() => {
    const suggestion = suggestionQuery.data
    if (
      !open
      || !suggestion
      || suggestion.type !== 'ISSUE_BREAKDOWN'
      || loadedSuggestionId.current === suggestion.id
    ) return
    loadedSuggestionId.current = suggestion.id
    setDraftItems(toEditableItems(suggestion.content))
    setAppliedIssueIds(suggestion.createdIssueIds ?? [])
    idempotencyKey.current = null
  }, [open, suggestionQuery.data])

  const generateMutation = useMutation({
    mutationFn: () => generateIssueBreakdown(issueId, {
      instruction: request.instruction.trim() || undefined,
      maxItems: request.maxItems,
      includeComments: request.includeComments,
      includeActivity: request.includeActivity,
    }),
    onSuccess: (suggestion) => {
      loadedSuggestionId.current = suggestion.id
      setDraftItems(toEditableItems(suggestion.content))
      setAppliedIssueIds([])
      idempotencyKey.current = null
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

  const applyMutation = useMutation({
    mutationFn: () => {
      const key = idempotencyKey.current ?? crypto.randomUUID()
      idempotencyKey.current = key
      return applyIssueBreakdown(suggestionId ?? '', {
        idempotencyKey: key,
        items: draftItems.map((item) => ({
          clientItemId: item.clientItemId,
          selected: item.selected,
          title: item.title.trim(),
          description: item.description.trim() || null,
          priority: item.priority,
          labelIds: item.labelIds,
          assigneeUserId: item.assigneeUserId,
          workflowStateId: item.workflowStateId,
          dueDate: item.dueDate,
        })),
      })
    },
    onSuccess: async (result) => {
      setAppliedIssueIds(result.createdIssueIds)
      idempotencyKey.current = null
      await queryClient.invalidateQueries({
        queryKey: ['ai-suggestion', workspaceId, suggestionId],
      })
      await onApplied(result.createdIssueIds)
    },
  })

  const suggestion = suggestionQuery.data
  const validation = useMemo(() => validateDraft(draftItems), [draftItems])
  const selectedCount = draftItems.filter((item) => item.selected).length
  const isDraft = suggestion?.status === 'DRAFT'

  function startAnotherBreakdown() {
    loadedSuggestionId.current = null
    idempotencyKey.current = null
    setDraftItems([])
    setAppliedIssueIds([])
    setRequest(DEFAULT_REQUEST)
    generateMutation.reset()
    applyMutation.reset()
    dismissMutation.reset()
    onSuggestionChange(null)
  }

  function updateItem(clientItemId: string, update: Partial<EditableBreakdownItem>) {
    setDraftItems((items) => items.map((item) => (
      item.clientItemId === clientItemId ? { ...item, ...update } : item
    )))
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
        aria-labelledby="ai-drawer-title"
      >
        <header className="ai-drawer-header">
          <div>
            <span className="ai-drawer-eyebrow"><Sparkles aria-hidden="true" /> AI Copilot</span>
            <h2 id="ai-drawer-title">Break down issue</h2>
          </div>
          <Button type="button" variant="ghost" size="icon" aria-label="Close" onClick={onClose}>
            <X aria-hidden="true" />
          </Button>
        </header>

        <div className="ai-drawer-body">
          {!suggestionId ? (
            <BreakdownRequestForm
              request={request}
              onChange={setRequest}
              onGenerate={() => generateMutation.mutate()}
              isGenerating={generateMutation.isPending}
              error={generateMutation.error}
            />
          ) : null}

          {suggestionId && suggestionQuery.isLoading ? (
            <InlineState>Loading saved AI draft.</InlineState>
          ) : null}
          {suggestionQuery.error ? <AiErrorNotice error={suggestionQuery.error} /> : null}

          {suggestion && suggestion.type !== 'ISSUE_BREAKDOWN' ? (
            <InlineNotice tone="warning">This suggestion is not an issue breakdown.</InlineNotice>
          ) : null}

          {suggestion?.type === 'ISSUE_BREAKDOWN' ? (
            <>
              <SuggestionSummary suggestion={suggestion} />
              {appliedIssueIds.length > 0 || suggestion.status === 'APPLIED' ? (
                <AppliedResult
                  issueIds={appliedIssueIds.length > 0
                    ? appliedIssueIds
                    : suggestion.createdIssueIds ?? []}
                  onAnother={startAnotherBreakdown}
                />
              ) : null}
              {suggestion.status === 'DISMISSED' || suggestion.status === 'EXPIRED' ? (
                <div className="ai-terminal-state">
                  <InlineNotice tone="warning">
                    This AI draft is {suggestion.status.toLowerCase()} and cannot be applied.
                  </InlineNotice>
                  <Button type="button" onClick={startAnotherBreakdown}>Generate another</Button>
                </div>
              ) : null}
              {isDraft ? (
                <>
                  <div className="ai-draft-list">
                    {draftItems.map((item, index) => (
                      <BreakdownItemEditor
                        key={item.clientItemId}
                        item={item}
                        index={index}
                        projectMembers={projectMembers}
                        projectLabels={projectLabels}
                        workflowStates={workflowStates}
                        onChange={(update) => updateItem(item.clientItemId, update)}
                      />
                    ))}
                  </div>
                  {validation ? <InlineNotice tone="warning">{validation}</InlineNotice> : null}
                  {applyMutation.error ? <AiErrorNotice error={applyMutation.error} /> : null}
                  {dismissMutation.error ? <AiErrorNotice error={dismissMutation.error} /> : null}
                  <footer className="ai-drawer-actions">
                    <Button
                      type="button"
                      variant="ghost"
                      disabled={dismissMutation.isPending || applyMutation.isPending}
                      onClick={() => dismissMutation.mutate()}
                    >
                      {dismissMutation.isPending ? <Loader2 className="auth-spin" aria-hidden="true" /> : null}
                      Dismiss
                    </Button>
                    <Button
                      type="button"
                      disabled={Boolean(validation) || applyMutation.isPending}
                      onClick={() => applyMutation.mutate()}
                    >
                      {applyMutation.isPending ? <Loader2 className="auth-spin" aria-hidden="true" /> : null}
                      Create {selectedCount} {selectedCount === 1 ? 'issue' : 'issues'}
                    </Button>
                  </footer>
                </>
              ) : null}
            </>
          ) : null}
        </div>
      </aside>
    </div>
  )
}

function BreakdownRequestForm({
  request,
  onChange,
  onGenerate,
  isGenerating,
  error,
}: {
  request: Required<IssueBreakdownRequest>
  onChange: (request: Required<IssueBreakdownRequest>) => void
  onGenerate: () => void
  isGenerating: boolean
  error: Error | null
}) {
  return (
    <form
      className="ai-request-form"
      onSubmit={(event) => {
        event.preventDefault()
        onGenerate()
      }}
    >
      <p>Generate an editable draft. Nothing is created until you review and apply it.</p>
      <label className="app-field">
        Additional instructions
        <textarea
          rows={4}
          maxLength={2_000}
          value={request.instruction}
          placeholder="Focus on backend, frontend, and test work."
          disabled={isGenerating}
          onChange={(event) => onChange({ ...request, instruction: event.target.value })}
        />
      </label>
      <label className="app-field">
        Maximum items
        <select
          value={request.maxItems}
          disabled={isGenerating}
          onChange={(event) => onChange({ ...request, maxItems: Number(event.target.value) })}
        >
          {[2, 3, 4, 5, 6, 7, 8].map((count) => (
            <option key={count} value={count}>{count}</option>
          ))}
        </select>
      </label>
      <label className="ai-check-row">
        <input
          type="checkbox"
          checked={request.includeComments}
          disabled={isGenerating}
          onChange={(event) => onChange({ ...request, includeComments: event.target.checked })}
        />
        Include recent comments
      </label>
      <label className="ai-check-row">
        <input
          type="checkbox"
          checked={request.includeActivity}
          disabled={isGenerating}
          onChange={(event) => onChange({ ...request, includeActivity: event.target.checked })}
        />
        Include recent activity
      </label>
      {error ? <AiErrorNotice error={error} /> : null}
      <Button type="submit" disabled={isGenerating}>
        {isGenerating ? <Loader2 className="auth-spin" aria-hidden="true" /> : <Sparkles aria-hidden="true" />}
        {isGenerating ? 'Generating draft' : 'Generate breakdown'}
      </Button>
    </form>
  )
}

function SuggestionSummary({
  suggestion,
}: {
  suggestion: AiSuggestion<IssueBreakdownContent>
}) {
  return (
    <section className="ai-suggestion-summary">
      <span>AI-generated draft — review before creating</span>
      {suggestion.content.overview ? <p>{suggestion.content.overview}</p> : null}
      {suggestion.metadata.contextTruncated ? (
        <InlineNotice tone="warning">Based on recent activity; some context was truncated.</InlineNotice>
      ) : null}
      {suggestion.content.warnings.map((warning) => (
        <InlineNotice key={warning} tone="warning">{warning}</InlineNotice>
      ))}
    </section>
  )
}

function BreakdownItemEditor({
  item,
  index,
  projectMembers,
  projectLabels,
  workflowStates,
  onChange,
}: {
  item: EditableBreakdownItem
  index: number
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  workflowStates: ProjectWorkflowState[]
  onChange: (update: Partial<EditableBreakdownItem>) => void
}) {
  return (
    <article className="ai-draft-item" data-selected={item.selected}>
      <header>
        <label className="ai-check-row">
          <input
            type="checkbox"
            checked={item.selected}
            onChange={(event) => onChange({ selected: event.target.checked })}
          />
          Task {index + 1}
        </label>
        <small>{item.clientItemId}</small>
      </header>
      <fieldset disabled={!item.selected}>
        <label className="app-field">
          Title
          <input
            value={item.title}
            maxLength={240}
            onChange={(event) => onChange({ title: event.target.value })}
          />
        </label>
        <label className="app-field">
          Description
          <textarea
            rows={4}
            maxLength={10_000}
            value={item.description}
            onChange={(event) => onChange({ description: event.target.value })}
          />
        </label>
        <div className="ai-draft-grid">
          <label className="app-field">
            Priority
            <select
              value={item.priority ?? ''}
              onChange={(event) => onChange({
                priority: event.target.value
                  ? event.target.value as IssuePriority
                  : null,
              })}
            >
              <option value="">No priority</option>
              {ISSUE_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>{PRIORITY_LABELS[priority]}</option>
              ))}
            </select>
          </label>
          <label className="app-field">
            Workflow state
            <select
              value={item.workflowStateId ?? ''}
              onChange={(event) => onChange({ workflowStateId: event.target.value || null })}
            >
              <option value="">Default Todo</option>
              {workflowStates.map((state) => (
                <option key={state.id} value={state.id}>{state.name}</option>
              ))}
            </select>
          </label>
          <label className="app-field">
            Assignee
            <select
              value={item.assigneeUserId ?? ''}
              onChange={(event) => onChange({ assigneeUserId: event.target.value || null })}
            >
              <option value="">Unassigned</option>
              {projectMembers.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {member.displayName || member.email}
                </option>
              ))}
            </select>
          </label>
          <label className="app-field">
            Due date
            <input
              type="date"
              value={item.dueDate ?? ''}
              onChange={(event) => onChange({ dueDate: event.target.value || null })}
            />
          </label>
        </div>
        {projectLabels.length > 0 ? (
          <fieldset className="ai-label-picker">
            <legend>Labels</legend>
            {projectLabels.map((label) => (
              <label key={label.id} className="ai-check-row">
                <input
                  type="checkbox"
                  checked={item.labelIds.includes(label.id)}
                  onChange={(event) => onChange({
                    labelIds: event.target.checked
                      ? [...item.labelIds, label.id]
                      : item.labelIds.filter((id) => id !== label.id),
                  })}
                />
                {label.name}
              </label>
            ))}
          </fieldset>
        ) : null}
      </fieldset>
      {item.acceptanceCriteria.length > 0 ? (
        <section className="ai-readonly-list">
          <strong>Acceptance criteria</strong>
          <ul>{item.acceptanceCriteria.map((criterion) => <li key={criterion}>{criterion}</li>)}</ul>
        </section>
      ) : null}
      {item.dependsOnClientItemIds.length > 0 ? (
        <p className="ai-dependencies">
          Depends on {item.dependsOnClientItemIds.join(', ')}
        </p>
      ) : null}
    </article>
  )
}

function AppliedResult({ issueIds, onAnother }: { issueIds: string[]; onAnother: () => void }) {
  return (
    <section className="ai-applied-result">
      <CheckCircle2 aria-hidden="true" />
      <div>
        <strong>{issueIds.length} {issueIds.length === 1 ? 'issue' : 'issues'} created</strong>
        <p>The board, issue list, activity, and analytics have been refreshed.</p>
        <Button type="button" variant="outline" onClick={onAnother}>Generate another</Button>
      </div>
    </section>
  )
}

function toEditableItems(content: IssueBreakdownContent): EditableBreakdownItem[] {
  return content.items.map((item) => ({
    clientItemId: item.clientItemId,
    selected: true,
    title: item.title,
    description: item.description ?? '',
    priority: item.priority ?? null,
    labelIds: [...(item.suggestedLabelIds ?? [])],
    assigneeUserId: item.suggestedAssigneeUserId ?? null,
    workflowStateId: null,
    dueDate: item.dueDate ?? null,
    acceptanceCriteria: [...(item.acceptanceCriteria ?? [])],
    dependsOnClientItemIds: [...(item.dependsOnClientItemIds ?? [])],
  }))
}

function validateDraft(items: EditableBreakdownItem[]) {
  const selected = items.filter((item) => item.selected)
  if (selected.length === 0) return 'Select at least one task.'
  if (selected.some((item) => !item.title.trim())) return 'Every selected task needs a title.'
  if (selected.some((item) => item.title.trim().length > 240)) return 'Task titles must be at most 240 characters.'
  if (selected.some((item) => item.description.length > 10_000)) return 'Task descriptions must be at most 10000 characters.'
  return null
}
