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

export type ProjectLabel = {
  id: string
  projectId: string
  name: string
  color: string
  createdAt: string
  updatedAt: string
}

export type WorkflowStateCategory = 'TODO' | 'IN_PROGRESS' | 'DONE' | string

export type ProjectWorkflowState = {
  id: string
  projectId: string
  name: string
  category: WorkflowStateCategory
  position: number
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
  workflowState: ProjectWorkflowState
  priority?: IssuePriority | null
  labels: ProjectLabel[]
  creator: AuthUser
  reporter?: AuthUser | null
  assignee?: AuthUser | null
  dueDate?: string | null
  archivedAt?: string | null
  boardPosition: number
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

export type BoardColumn = {
  workflowState: ProjectWorkflowState
  issues: IssueSummary[]
}

export type ProjectBoard = {
  projectId: string
  columns: BoardColumn[]
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

export type UpdateProjectMemberRequest = {
  role: 'OWNER' | 'MEMBER'
}

export type CreateProjectLabelRequest = {
  name: string
  color?: string
}

export type CreateProjectWorkflowStateRequest = {
  name: string
  category: WorkflowStateCategory
}

export type UpdateProjectWorkflowStateRequest = {
  name: string
  category: WorkflowStateCategory
}

export type ReorderProjectWorkflowStatesRequest = {
  workflowStateIds: string[]
}

export type CreateIssueRequest = {
  projectId: string
  title: string
  description?: string
  status?: IssueStatus
  workflowStateId?: string
  priority?: IssuePriority | null
  labelIds?: string[]
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
  workflowStateId?: string
  priority?: IssuePriority | null
  labelIds?: string[]
  assigneeUserId?: string | null
  dueDate?: string | null
}

export type MoveIssueStateRequest = {
  workflowStateId: string
}

export type ReorderIssuesRequest = {
  issueId: string
  workflowStateId: string
  orderedIssueIds: string[]
}

export type ListIssuesFilters = {
  status?: IssueStatus
  workflowStateId?: string
  priority?: IssuePriority
  assigneeUserId?: string
  labelId?: string
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

export function updateProjectMember(
  projectId: string,
  memberId: string,
  request: UpdateProjectMemberRequest,
) {
  return api.patch<ProjectMember>(`/projects/${projectId}/members/${memberId}`, request)
}

export function removeProjectMember(projectId: string, memberId: string) {
  return api.delete<void>(`/projects/${projectId}/members/${memberId}`)
}

export function listWorkspaceMembers() {
  return api.get<WorkspaceMember[]>('/workspaces/current/members')
}

export function listProjectLabels(projectId: string) {
  return api.get<ProjectLabel[]>(`/projects/${projectId}/labels`)
}

export function createProjectLabel(projectId: string, request: CreateProjectLabelRequest) {
  return api.post<ProjectLabel>(`/projects/${projectId}/labels`, request)
}

export function listProjectWorkflowStates(projectId: string) {
  return api.get<ProjectWorkflowState[]>(`/projects/${projectId}/workflow-states`)
}

export function createProjectWorkflowState(
  projectId: string,
  request: CreateProjectWorkflowStateRequest,
) {
  return api.post<ProjectWorkflowState>(`/projects/${projectId}/workflow-states`, request)
}

export function updateProjectWorkflowState(
  projectId: string,
  workflowStateId: string,
  request: UpdateProjectWorkflowStateRequest,
) {
  return api.patch<ProjectWorkflowState>(
    `/projects/${projectId}/workflow-states/${workflowStateId}`,
    request,
  )
}

export function reorderProjectWorkflowStates(
  projectId: string,
  request: ReorderProjectWorkflowStatesRequest,
) {
  return api.patch<ProjectWorkflowState[]>(
    `/projects/${projectId}/workflow-states/order`,
    request,
  )
}

export function listIssues(projectId: string, filters: ListIssuesFilters = {}) {
  const params = new URLSearchParams({ projectId })

  if (filters.status) {
    params.set('status', filters.status)
  }
  if (filters.workflowStateId) {
    params.set('workflowStateId', filters.workflowStateId)
  }
  if (filters.priority) {
    params.set('priority', filters.priority)
  }
  if (filters.assigneeUserId) {
    params.set('assigneeUserId', filters.assigneeUserId)
  }
  if (filters.labelId) {
    params.set('labelId', filters.labelId)
  }
  if (filters.q) {
    params.set('q', filters.q)
  }

  return api.get<IssueSummary[]>(`/issues?${params.toString()}`)
}

export function getProjectBoard(projectId: string) {
  const params = new URLSearchParams({ projectId })
  return api.get<ProjectBoard>(`/issues/board?${params.toString()}`)
}

export function createIssue(request: CreateIssueRequest) {
  return api.post<IssueSummary>('/issues', request)
}

export function updateIssue(issueId: string, request: UpdateIssueRequest) {
  return api.patch<IssueDetail>(`/issues/${issueId}`, request)
}

export function moveIssueState(issueId: string, request: MoveIssueStateRequest) {
  return api.patch<IssueSummary>(`/issues/${issueId}/state`, request)
}

export function reorderIssues(request: ReorderIssuesRequest) {
  return api.patch<ProjectBoard>('/issues/reorder', request)
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
