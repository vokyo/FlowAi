import { api } from '@/api/client'
import type { IssuePriority } from '@/api/work-api'

/** The limits the backend enforces, so the form can say so before it asks. */
export const AGENT_GOAL_MAX_LENGTH = 500
export const AGENT_FEEDBACK_MAX_LENGTH = 1000
export const AGENT_MAX_VERSIONS = 5

export type AgentRunState = 'REVIEWING' | 'APPROVED' | 'CANCELLED'
export type AgentRunStatus = 'PLANNED' | 'INSUFFICIENT_INFO' | 'FAILED'

export type ProjectPlanItem = {
  clientItemId: string
  title: string
  description?: string | null
  priority?: IssuePriority | null
  suggestedAssigneeUserId?: string | null
  dueDate?: string | null
}

export type ProjectPlanExistingIssue = {
  issueId: string
  reason?: string | null
}

/** existingIssues already cover part of the goal; only items become new issues. */
export type ProjectPlan = {
  overview?: string | null
  existingIssues?: ProjectPlanExistingIssue[] | null
  items: ProjectPlanItem[]
}

export type AgentRunStats = {
  decisionRounds?: number | null
  toolCalls?: number | null
}

/**
 * How starting or revising a run ended. PLANNED saved a version; INSUFFICIENT_INFO
 * saved nothing and says what was missing. A failed run is an error response.
 */
export type AgentRunResponse = {
  runId: string
  status: AgentRunStatus
  version?: number | null
  approvable?: boolean | null
  rejectionReason?: string | null
  contentHash?: string | null
  plan?: ProjectPlan | null
  missing?: string[] | null
  stats?: AgentRunStats | null
}

export type AgentPlanVersion = {
  version: number
  approvable: boolean
  rejectionReason?: string | null
  contentHash: string
  plan: ProjectPlan
  /** Empty unless this is the version that was approved. */
  createdIssueIds: string[]
  createdAt: string
}

export type AgentRunDetail = {
  runId: string
  projectId: string
  goal: string
  generatedOn: string
  state: AgentRunState
  latestVersion: number
  versions: AgentPlanVersion[]
  createdAt: string
  updatedAt: string
}

export type AgentRunSummary = {
  runId: string
  projectId: string
  goal: string
  state: AgentRunState
  latestVersion: number
  createdAt: string
  updatedAt: string
}

export type AgentApprovalResponse = {
  runId: string
  version: number
  createdIssueIds: string[]
  approvedAt: string
}

export function startAgentRun(projectId: string, goal: string) {
  return api.post<AgentRunResponse>('/agent/runs', { projectId, goal })
}

export function listAgentRuns(projectId: string) {
  return api.get<AgentRunSummary[]>(`/agent/runs?projectId=${encodeURIComponent(projectId)}`)
}

export function getAgentRun(runId: string) {
  return api.get<AgentRunDetail>(`/agent/runs/${runId}`)
}

export function reviseAgentRun(runId: string, request: { basedOnVersion: number; feedback: string }) {
  return api.post<AgentRunResponse>(`/agent/runs/${runId}/revisions`, request)
}

export function approveAgentRun(runId: string, request: { version: number; contentHash: string }) {
  return api.post<AgentApprovalResponse>(`/agent/runs/${runId}/approve`, request)
}

export function cancelAgentRun(runId: string) {
  return api.post<AgentRunDetail>(`/agent/runs/${runId}/cancel`)
}
