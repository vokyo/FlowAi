import { useEffect, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Check, Copy, Loader2, RefreshCw, Send, Trash2 } from 'lucide-react'
import type { AuthWorkspace } from '@/auth/auth-api'
import { Button } from '@/components/ui/button'
import type { WorkspaceInvitation } from '@/workspace/workspace-api'
import {
  createWorkspaceInvitationFormSchema,
  type CreateWorkspaceInvitationFormValues,
} from '@/features/project-shell/project-model'
import {
  canManageInvitationRole,
  formatDate,
  invitationRolesFor,
  titleCaseWorkspaceRole,
} from '@/features/project-shell/display-utils'
import { ErrorState, InlineNotice, InlineState, ModalShell } from '@/features/project-shell/feature-ui'

export function WorkspaceInvitationsDialog({
  isOpen,
  currentWorkspace,
  invitations,
  latestInvitationLink,
  isLoading,
  hasMore,
  isLoadingMore,
  isMutating,
  error,
  onSubmit,
  onReissue,
  onRevoke,
  onLoadMore,
  onClose,
}: {
  isOpen: boolean
  currentWorkspace: AuthWorkspace | null
  invitations: WorkspaceInvitation[]
  latestInvitationLink: string | null
  isLoading: boolean
  hasMore: boolean
  isLoadingMore: boolean
  isMutating: boolean
  error: Error | null
  onSubmit: (values: CreateWorkspaceInvitationFormValues) => Promise<void>
  onReissue: (invitationId: string) => void
  onRevoke: (invitationId: string) => void
  onLoadMore: () => void
  onClose: () => void
}) {
  const [copiedInvitationLink, setCopiedInvitationLink] = useState<string | null>(null)
  const allowedRoles = invitationRolesFor(currentWorkspace?.role)
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateWorkspaceInvitationFormValues>({
    resolver: zodResolver(createWorkspaceInvitationFormSchema),
    defaultValues: { email: '', role: allowedRoles[0] ?? 'MEMBER' },
  })

  useEffect(() => {
    if (isOpen) {
      reset({ email: '', role: allowedRoles[0] ?? 'MEMBER' })
    }
  }, [allowedRoles, isOpen, reset])

  if (!isOpen) return null

  async function copyLatestLink() {
    if (!latestInvitationLink) return
    await navigator.clipboard.writeText(latestInvitationLink)
    setCopiedInvitationLink(latestInvitationLink)
  }

  async function submitInvitation(values: CreateWorkspaceInvitationFormValues) {
    try {
      await onSubmit(values)
      reset({ email: '', role: allowedRoles[0] ?? 'MEMBER' })
    } catch {
      // The mutation error is rendered below so the form values remain available.
    }
  }

  return (
    <ModalShell
      title="Workspace invitations"
      eyebrow={currentWorkspace?.name ?? 'Workspace'}
      onClose={onClose}
      variant="members"
      isCloseDisabled={isMutating}
    >
      <form
        className="workspace-invitation-form"
        onSubmit={handleSubmit(submitInvitation)}
        noValidate
      >
        <label className="app-field">
          Email
          <input
            type="email"
            placeholder="teammate@example.com"
            disabled={isMutating}
            {...register('email')}
          />
        </label>
        <label className="app-field">
          Role
          <select disabled={isMutating} {...register('role')}>
            {allowedRoles.map((role) => (
              <option key={role} value={role}>{titleCaseWorkspaceRole(role)}</option>
            ))}
          </select>
        </label>
        <Button type="submit" disabled={isMutating}>
          {isMutating ? <Loader2 className="auth-spin" /> : <Send />}
          Create invite
        </Button>
      </form>
      {errors.email?.message ? <InlineNotice tone="warning">{errors.email.message}</InlineNotice> : null}
      {errors.role?.message ? <InlineNotice tone="warning">{errors.role.message}</InlineNotice> : null}

      {latestInvitationLink ? (
        <div className="workspace-invitation-link">
          <span>Share this link</span>
          <div>
            <input readOnly value={latestInvitationLink} aria-label="Workspace invitation link" />
            <Button type="button" variant="outline" onClick={copyLatestLink}>
              {copiedInvitationLink === latestInvitationLink ? <Check /> : <Copy />}
              {copiedInvitationLink === latestInvitationLink ? 'Copied' : 'Copy'}
            </Button>
          </div>
          <small>For security, the link is shown only for this newly issued token.</small>
        </div>
      ) : null}

      {error ? <ErrorState error={error} /> : null}
      <div className="workspace-invitation-list">
        <div className="workspace-invitation-list-header">
          <strong>Invitation history</strong>
          <span>{invitations.length}</span>
        </div>
        {isLoading ? <InlineState>Loading invitations.</InlineState> : null}
        {!isLoading && invitations.length === 0 ? (
          <InlineState>No invitations yet.</InlineState>
        ) : null}
        {invitations.map((invitation) => {
          const canManageRole = canManageInvitationRole(currentWorkspace?.role, invitation.role)
          const canReissue = invitation.status === 'PENDING' || invitation.status === 'EXPIRED'
          const canRevoke = invitation.status !== 'ACCEPTED' && invitation.status !== 'REVOKED'
          return (
            <div className="workspace-invitation-row" key={invitation.id}>
              <div>
                <strong>{invitation.email}</strong>
                <span>
                  {titleCaseWorkspaceRole(invitation.role)} / {invitation.status.toLowerCase()} / expires {formatDate(invitation.expiresAt)}
                </span>
              </div>
              <div className="workspace-invitation-row-actions">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  title="Reissue invitation"
                  aria-label={`Reissue invitation for ${invitation.email}`}
                  disabled={!canManageRole || !canReissue || isMutating}
                  onClick={() => onReissue(invitation.id)}
                >
                  <RefreshCw />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  title="Revoke invitation"
                  aria-label={`Revoke invitation for ${invitation.email}`}
                  disabled={!canManageRole || !canRevoke || isMutating}
                  onClick={() => {
                    if (window.confirm(`Revoke the invitation for ${invitation.email}?`)) {
                      onRevoke(invitation.id)
                    }
                  }}
                >
                  <Trash2 />
                </Button>
              </div>
            </div>
          )
        })}
        {hasMore ? (
          <div className="pagination-actions">
            <Button
              type="button"
              variant="ghost"
              disabled={isLoadingMore || isMutating}
              onClick={onLoadMore}
            >
              {isLoadingMore ? <Loader2 className="auth-spin" aria-hidden="true" /> : null}
              {isLoadingMore ? 'Loading invitations' : 'Load more invitations'}
            </Button>
          </div>
        ) : null}
      </div>
    </ModalShell>
  )
}


