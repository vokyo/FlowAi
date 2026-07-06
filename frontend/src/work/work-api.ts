import { api } from '@/api/client'
import type { AuthUser } from '@/auth/auth-api'

export type Project = {
  id: string
  name: string
  description?: string | null
  createdAt: string
  updatedAt: string
}

export type IssueStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'ARCHIVED' | string
export type IssuePriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT' | string

export type IssueSummary = {
  id: string
  projectId: string
  title: string
  description?: string | null
  status: IssueStatus
  priority?: IssuePriority | null
  createdAt: string
  updatedAt: string
  commentCount?: number | null
}

export type IssueComment = {
  id: string
  issueId: string
  author: AuthUser
  body: string
  createdAt: string
}

export type IssueDetail = IssueSummary & {
  comments: IssueComment[]
}

export type ActivityEventType =
  | 'PROJECT_CREATED'
  | 'ISSUE_CREATED'
  | 'COMMENT_CREATED'
  | 'ISSUE_STATUS_CHANGED'
  | string

export type ActivityEvent = {
  id: string
  eventType: ActivityEventType
  actor: AuthUser
  metadata?: Record<string, unknown> | null
  createdAt: string
}

export type CreateProjectRequest = {
  name: string
  description?: string
}

export type CreateIssueRequest = {
  projectId: string
  title: string
  description?: string
}

export type CreateCommentRequest = {
  body: string
}

export function listProjects() {
  return api.get<Project[]>('/projects')
}

export function createProject(request: CreateProjectRequest) {
  return api.post<Project>('/projects', request)
}

export function listIssues(projectId: string) {
  return api.get<IssueSummary[]>(`/issues?projectId=${encodeURIComponent(projectId)}`)
}

export function createIssue(request: CreateIssueRequest) {
  return api.post<IssueSummary>('/issues', request)
}

export function getIssue(issueId: string) {
  return api.get<IssueDetail>(`/issues/${issueId}`)
}

export function createIssueComment(issueId: string, request: CreateCommentRequest) {
  return api.post<IssueComment>(`/issues/${issueId}/comments`, request)
}

export function listIssueActivities(issueId: string) {
  return api.get<ActivityEvent[]>(`/issues/${issueId}/activities`)
}
