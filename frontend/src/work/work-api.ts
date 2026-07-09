import { api } from '@/api/client'
import type { AuthUser } from '@/auth/auth-api'

export type Project = {
  id: string
  name: string
  description?: string | null
  createdAt: string
  updatedAt: string
}

export type ProjectMemberRole = 'OWNER' | 'MEMBER' | string
export type MembershipStatus = 'ACTIVE' | 'DISABLED' | string

export type ProjectMember = {
  id: string
  userId: string
  email: string
  displayName: string
  role: ProjectMemberRole
  status: MembershipStatus
  joinedAt: string
}

export type WorkspaceMember = {
  id: string
  userId: string
  email: string
  displayName: string
  role: string
  status: MembershipStatus
  joinedAt: string
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
  creator: AuthUser
  reporter?: AuthUser | null
  assignee?: AuthUser | null
  dueDate?: string | null
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
  | 'ISSUE_TITLE_CHANGED'
  | 'ISSUE_PRIORITY_CHANGED'
  | 'ISSUE_ASSIGNEE_CHANGED'
  | 'ISSUE_DUE_DATE_CHANGED'
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

export type AddProjectMemberRequest = {
  userId: string
  role: 'MEMBER'
}

export type CreateIssueRequest = {
  projectId: string
  title: string
  description?: string
  status?: IssueStatus
  priority?: IssuePriority | null
  assigneeUserId?: string | null
  dueDate?: string | null
}

export type CreateCommentRequest = {
  body: string
}

export type UpdateIssueRequest = {
  title?: string
  description?: string | null
  status?: IssueStatus
  priority?: IssuePriority | null
  assigneeUserId?: string | null
  dueDate?: string | null
}

export type ListIssuesFilters = {
  status?: IssueStatus
  priority?: IssuePriority
  assigneeUserId?: string
  q?: string
}

export function listProjects() {
  return api.get<Project[]>('/projects')
}

export function getProject(projectId: string) {
  return api.get<Project>(`/projects/${projectId}`)
}

export function createProject(request: CreateProjectRequest) {
  return api.post<Project>('/projects', request)
}

export function listProjectMembers(projectId: string) {
  return api.get<ProjectMember[]>(`/projects/${projectId}/members`)
}

export function addProjectMember(projectId: string, request: AddProjectMemberRequest) {
  return api.post<ProjectMember>(`/projects/${projectId}/members`, request)
}

export function listWorkspaceMembers() {
  return api.get<WorkspaceMember[]>('/workspaces/current/members')
}

export function listIssues(projectId: string, filters: ListIssuesFilters = {}) {
  const params = new URLSearchParams({ projectId })

  if (filters.status) {
    params.set('status', filters.status)
  }
  if (filters.priority) {
    params.set('priority', filters.priority)
  }
  if (filters.assigneeUserId) {
    params.set('assigneeUserId', filters.assigneeUserId)
  }
  if (filters.q) {
    params.set('q', filters.q)
  }

  return api.get<IssueSummary[]>(`/issues?${params.toString()}`)
}

export function createIssue(request: CreateIssueRequest) {
  return api.post<IssueSummary>('/issues', request)
}

export function updateIssue(issueId: string, request: UpdateIssueRequest) {
  return api.patch<IssueDetail>(`/issues/${issueId}`, request)
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
