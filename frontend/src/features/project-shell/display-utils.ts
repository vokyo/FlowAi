import { ApiError } from '@/api/client'
import type { ActivityEvent, IssueDetail, IssueSummary, WorkflowStateCategory } from '@/work/work-api'
import type { WorkspaceInvitationCreated } from '@/workspace/workspace-api'
import {
  ADMIN_INVITATION_ROLES,
  ISSUE_PRIORITIES,
  ISSUE_STATUSES,
  OWNER_INVITATION_ROLES,
  PRIORITY_LABELS,
  STATUS_LABELS,
  WORKFLOW_STATE_CATEGORIES,
  type InvitableWorkspaceRole,
} from './project-model'

export function statusForIcon(issue: IssueSummary | IssueDetail) {
  return issue.status === 'ARCHIVED' ? 'ARCHIVED' : issue.workflowState.category
}

export function getInitials(value: string) {
  return value.trim().split(/\s+/).slice(0, 2).map((part) => part.charAt(0).toUpperCase()).join('')
}

export function getErrorMessage(error: Error) {
  return error instanceof ApiError ? error.message : 'Unable to load this workspace data.'
}

export function getProjectMemberMutationErrorMessage(
  error: Error,
  action: 'add' | 'update' | 'remove',
) {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return action === 'add'
        ? 'This member is already active in the project.'
        : 'A project must have at least one active owner.'
    }
    if (error.status === 403) return 'You do not have permission to manage project members.'
    if (error.status === 404) {
      return action === 'add'
        ? 'This project or workspace member is no longer available.'
        : 'This project member is no longer available.'
    }
    return error.message
  }

  if (action === 'update') return 'Unable to update this project member.'
  if (action === 'remove') return 'Unable to remove this project member.'
  return 'Unable to add this project member.'
}

export function formatStatus(status: string) {
  if (isKnownStatus(status)) return STATUS_LABELS[status]
  return status.toLowerCase().split('_').map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(' ')
}

export function formatPriority(priority: string) {
  return isKnownPriority(priority) ? PRIORITY_LABELS[priority] : formatStatus(priority)
}

export function formatProjectRole(role: string) {
  return formatStatus(role)
}

function isKnownStatus(status: string): status is (typeof ISSUE_STATUSES)[number] {
  return ISSUE_STATUSES.includes(status as (typeof ISSUE_STATUSES)[number])
}

export function toWorkflowStateCategoryInput(
  category: WorkflowStateCategory,
): (typeof WORKFLOW_STATE_CATEGORIES)[number] {
  return WORKFLOW_STATE_CATEGORIES.includes(category as (typeof WORKFLOW_STATE_CATEGORIES)[number])
    ? (category as (typeof WORKFLOW_STATE_CATEGORIES)[number])
    : 'IN_PROGRESS'
}

function isKnownPriority(priority: string): priority is (typeof ISSUE_PRIORITIES)[number] {
  return ISSUE_PRIORITIES.includes(priority as (typeof ISSUE_PRIORITIES)[number])
}

export function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

export function invitationRolesFor(role: string | undefined): InvitableWorkspaceRole[] {
  if (role === 'OWNER') return OWNER_INVITATION_ROLES
  if (role === 'ADMIN') return ADMIN_INVITATION_ROLES
  return []
}

export function canManageInvitationRole(
  actorRole: string | undefined,
  invitationRole: InvitableWorkspaceRole,
) {
  return invitationRolesFor(actorRole).includes(invitationRole)
}

export function titleCaseWorkspaceRole(role: string) {
  return role.charAt(0) + role.slice(1).toLowerCase()
}

export function invitationUrl(invitation: WorkspaceInvitationCreated) {
  return `${window.location.origin}/invite/${invitation.token}`
}

export function formatDateOnly(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
  }).format(new Date(`${value}T00:00:00`))
}

export function formatActivity(activity: ActivityEvent) {
  const actor = activity.actor.displayName || activity.actor.email
  const metadata = activity.metadata ?? {}
  switch (activity.eventType) {
    case 'PROJECT_CREATED':
      return `${actor} created project ${readMetadata(metadata, 'projectName') ?? 'this project'}`
    case 'ISSUE_CREATED': return `${actor} created this issue`
    case 'COMMENT_CREATED': return `${actor} commented`
    case 'ISSUE_STATUS_CHANGED': {
      const fromStatus = readMetadata(metadata, 'fromStatus')
      const toStatus = readMetadata(metadata, 'toStatus')
      return fromStatus && toStatus
        ? `${actor} changed status from ${formatStatus(fromStatus)} to ${formatStatus(toStatus)}`
        : `${actor} changed issue status`
    }
    case 'ISSUE_TITLE_CHANGED': return `${actor} renamed this issue`
    case 'ISSUE_PRIORITY_CHANGED':
      return `${actor} changed priority from ${formatOptionalPriority(readMetadata(metadata, 'fromPriority'))} to ${formatOptionalPriority(readMetadata(metadata, 'toPriority'))}`
    case 'ISSUE_ASSIGNEE_CHANGED':
      return `${actor} changed assignee from ${readMetadata(metadata, 'fromAssigneeName') ?? 'Unassigned'} to ${readMetadata(metadata, 'toAssigneeName') ?? 'Unassigned'}`
    case 'ISSUE_DUE_DATE_CHANGED':
      return `${actor} changed due date from ${formatOptionalDueDate(readMetadata(metadata, 'fromDueDate'))} to ${formatOptionalDueDate(readMetadata(metadata, 'toDueDate'))}`
    default: return `${actor} recorded ${formatStatus(activity.eventType)}`
  }
}

function formatOptionalPriority(priority: string | undefined) {
  return priority ? formatPriority(priority) : 'No priority'
}

function formatOptionalDueDate(dueDate: string | undefined) {
  return dueDate ? formatDateOnly(dueDate) : 'No due date'
}

function readMetadata(metadata: Record<string, unknown>, key: string) {
  const value = metadata[key]
  return typeof value === 'string' ? value : undefined
}
