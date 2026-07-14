import { api } from '@/api/client'
import type { CursorPage } from '@/api/pagination'
import type { AuthResponse, AuthWorkspace } from '@/auth/auth-api'

export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'GUEST'
export type WorkspaceInvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'

export type WorkspaceInvitation = {
  id: string
  email: string
  role: Exclude<WorkspaceRole, 'OWNER'>
  status: WorkspaceInvitationStatus
  expiresAt: string
  acceptedAt?: string | null
  createdAt: string
}

export type WorkspaceInvitationCreated = WorkspaceInvitation & {
  token: string
}

export type WorkspaceInvitationPreview = {
  workspaceName: string
  email: string
  role: Exclude<WorkspaceRole, 'OWNER'>
  status: WorkspaceInvitationStatus
  expiresAt: string
}

export function listWorkspaces() {
  return api.get<AuthWorkspace[]>('/workspaces')
}

export function createWorkspace(request: { name: string }) {
  return api.post<AuthWorkspace>('/workspaces', request)
}

export function switchWorkspace(workspaceId: string) {
  return api.post<AuthResponse>(`/workspaces/${workspaceId}/switch`)
}

export function listWorkspaceInvitations(cursor?: string | null, limit = 50) {
  const params = new URLSearchParams({ limit: String(limit) })
  if (cursor) {
    params.set('cursor', cursor)
  }
  return api.get<CursorPage<WorkspaceInvitation>>(
    `/workspaces/current/invitations?${params.toString()}`,
  )
}

export function createWorkspaceInvitation(request: {
  email: string
  role: Exclude<WorkspaceRole, 'OWNER'>
}) {
  return api.post<WorkspaceInvitationCreated>('/workspaces/current/invitations', request)
}

export function reissueWorkspaceInvitation(invitationId: string) {
  return api.post<WorkspaceInvitationCreated>(
    `/workspaces/current/invitations/${invitationId}/reissue`,
  )
}

export function revokeWorkspaceInvitation(invitationId: string) {
  return api.delete<void>(`/workspaces/current/invitations/${invitationId}`)
}

export function updateWorkspaceMember(
  memberId: string,
  request: { role?: WorkspaceRole; status?: 'ACTIVE' | 'DISABLED' },
) {
  return api.patch<import('@/work/work-api').WorkspaceMember>(
    `/workspaces/current/members/${memberId}`,
    request,
  )
}

export function removeWorkspaceMember(memberId: string) {
  return api.delete<void>(`/workspaces/current/members/${memberId}`)
}

export function getWorkspaceInvitationPreview(token: string) {
  return api.get<WorkspaceInvitationPreview>(`/workspace-invitations/${token}`, {
    auth: false,
  })
}

export function acceptWorkspaceInvitation(token: string) {
  return api.post<AuthResponse>(`/workspace-invitations/${token}/accept`)
}
