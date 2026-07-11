import { api } from '@/api/client'
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

export function switchWorkspace(workspaceId: string, refreshToken: string) {
  return api.post<AuthResponse>(`/workspaces/${workspaceId}/switch`, { refreshToken })
}

export function listWorkspaceInvitations() {
  return api.get<WorkspaceInvitation[]>('/workspaces/current/invitations')
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

export function getWorkspaceInvitationPreview(token: string) {
  return api.get<WorkspaceInvitationPreview>(`/workspace-invitations/${token}`, {
    auth: false,
  })
}

export function acceptWorkspaceInvitation(token: string, refreshToken: string) {
  return api.post<AuthResponse>(`/workspace-invitations/${token}/accept`, {
    refreshToken,
  })
}
