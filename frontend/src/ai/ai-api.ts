import { api } from '@/api/client'
import type { IssuePriority } from '@/work/work-api'

export type AiSuggestionType = 'ISSUE_BREAKDOWN' | 'ISSUE_SUMMARY' | 'PROJECT_SUMMARY'
export type AiSuggestionStatus = 'DRAFT' | 'APPLIED' | 'DISMISSED' | 'EXPIRED'

export type AiStatus = {
  enabled: boolean
  breakdownAvailable: boolean
  issueSummaryAvailable: boolean
  projectSummaryAvailable: boolean
  agentAvailable: boolean
  disabledReason?: string | null
}

export type AiSuggestionMetadata = {
  promptVersion: string
  generatedAt: string
  contextTruncated: boolean
  provider?: string | null
  model?: string | null
  inputTokens?: number | null
  outputTokens?: number | null
}

export type AiSuggestion<TContent> = {
  id: string
  type: AiSuggestionType
  status: AiSuggestionStatus
  projectId: string
  sourceIssueId?: string | null
  content: TContent
  metadata: AiSuggestionMetadata
  createdIssueIds?: string[]
  createdAt: string
  expiresAt: string
  appliedAt?: string | null
  dismissedAt?: string | null
}

export type IssueBreakdownItem = {
  clientItemId: string
  title: string
  description?: string | null
  priority?: IssuePriority | null
  acceptanceCriteria: string[]
  suggestedLabelIds: string[]
  suggestedAssigneeUserId?: string | null
  dueDate?: string | null
  dependsOnClientItemIds: string[]
}

export type IssueBreakdownContent = {
  overview?: string | null
  items: IssueBreakdownItem[]
  warnings: string[]
}

export type IssueBreakdownRequest = {
  instruction?: string
  maxItems?: number
  includeComments?: boolean
  includeActivity?: boolean
}

export type EditableBreakdownItem = {
  clientItemId: string
  selected: boolean
  title: string
  description: string
  priority: IssuePriority | null
  labelIds: string[]
  assigneeUserId: string | null
  workflowStateId: string | null
  dueDate: string | null
  acceptanceCriteria: string[]
  dependsOnClientItemIds: string[]
}

export type ApplyIssueBreakdownRequest = {
  idempotencyKey: string
  items: Array<{
    clientItemId: string
    selected: boolean
    title: string
    description: string | null
    priority: IssuePriority | null
    labelIds: string[]
    assigneeUserId: string | null
    workflowStateId: string | null
    dueDate: string | null
  }>
}

export type ApplySuggestionResponse = {
  suggestionId: string
  status: 'APPLIED'
  createdIssueIds: string[]
  appliedAt: string
}

export type IssueSummarySourceStats = {
  commentsUsed: number
  activityEventsUsed: number
  commentsTruncated: boolean
  activityTruncated: boolean
  contextTruncated: boolean
}

export type IssueSummaryContent = {
  summary: string
  decisions: string[]
  openQuestions: string[]
  blockers: string[]
  nextActions: string[]
  sourceStats: IssueSummarySourceStats
}

export type IssueSummaryRequest = {
  includeComments: boolean
  includeActivity: boolean
}

export type ProjectSummarySourceStats = {
  activeIssuesUsed: number
  totalActiveIssues: number
  rangeDays: 7 | 30 | 90
  contextTruncated: boolean
}

export type ProjectSummaryContent = {
  executiveSummary: string
  progressHighlights: string[]
  currentRisks: string[]
  blockers: string[]
  workloadObservations: string[]
  recommendedNextActions: string[]
  sourceStats: ProjectSummarySourceStats
}

export type ProjectSummaryRequest = {
  rangeDays: 7 | 30 | 90
  focus?: string
}

export function getAiStatus() {
  return api.get<AiStatus>('/ai/status')
}

export function generateIssueBreakdown(
  issueId: string,
  request: IssueBreakdownRequest,
) {
  return api.post<AiSuggestion<IssueBreakdownContent>>(
    `/ai/issues/${issueId}/breakdown`,
    request,
  )
}

export function getAiSuggestion<TContent>(suggestionId: string) {
  return api.get<AiSuggestion<TContent>>(`/ai/suggestions/${suggestionId}`)
}

export function dismissAiSuggestion(suggestionId: string) {
  return api.post<AiSuggestion<unknown>>(`/ai/suggestions/${suggestionId}/dismiss`)
}

export function applyIssueBreakdown(
  suggestionId: string,
  request: ApplyIssueBreakdownRequest,
) {
  return api.post<ApplySuggestionResponse>(
    `/ai/suggestions/${suggestionId}/apply`,
    request,
  )
}

export function generateIssueSummary(
  issueId: string,
  request: IssueSummaryRequest,
) {
  return api.post<AiSuggestion<IssueSummaryContent>>(
    `/ai/issues/${issueId}/summary`,
    request,
  )
}

export function generateProjectSummary(
  projectId: string,
  request: ProjectSummaryRequest,
) {
  return api.post<AiSuggestion<ProjectSummaryContent>>(
    `/ai/projects/${projectId}/summary`,
    request,
  )
}
