import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Building2, CheckCircle2, Loader2, LogOut, ShieldCheck } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '@/api/client'
import { getCurrentSession, logout } from '@/auth/auth-api'
import {
  hasAccessToken,
  setAccessToken,
} from '@/auth/access-token'
import { clearClientSession } from '@/auth/client-session'
import { Button } from '@/components/ui/button'
import {
  acceptWorkspaceInvitation,
  getWorkspaceInvitationPreview,
} from '@/workspace/workspace-api'

type InvitationPageProps = {
  onSessionChanged: () => void
}

export function InvitationPage({ onSessionChanged }: InvitationPageProps) {
  const { token = '' } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isAuthenticated = hasAccessToken()
  const returnTo = `/invite/${token}`

  const invitationQuery = useQuery({
    queryKey: ['workspace-invitation-preview', token],
    queryFn: () => getWorkspaceInvitationPreview(token),
    enabled: Boolean(token),
    retry: false,
  })

  const sessionQuery = useQuery({
    queryKey: ['current-session'],
    queryFn: getCurrentSession,
    enabled: isAuthenticated,
    retry: false,
  })

  const acceptMutation = useMutation({
    mutationFn: () => acceptWorkspaceInvitation(token),
    onSuccess: async (response) => {
      setAccessToken(response.accessToken)
      await queryClient.cancelQueries()
      queryClient.clear()
      onSessionChanged()
      navigate('/app', { replace: true })
    },
  })

  async function switchAccount() {
    try {
      await logout()
    } catch {
      // Switching accounts must still clear the in-memory session if logout fails.
    } finally {
      clearClientSession(queryClient)
      onSessionChanged()
      navigate(`/login?returnTo=${encodeURIComponent(returnTo)}`, { replace: true })
    }
  }

  const invitation = invitationQuery.data
  const session = sessionQuery.data
  const isMatchingAccount = Boolean(
    invitation && session && invitation.email.toLowerCase() === session.user.email.toLowerCase(),
  )

  return (
    <main className="auth-screen invitation-screen">
      <section className="auth-panel invitation-panel">
        <div className="invitation-mark" aria-hidden="true">
          <Building2 />
        </div>
        {invitationQuery.isLoading ? (
          <InvitationState icon={<Loader2 className="auth-spin" />} title="Loading invitation" />
        ) : invitationQuery.error ? (
          <InvitationState
            icon={<ShieldCheck />}
            title="Invitation unavailable"
            body={errorMessage(invitationQuery.error, 'This invitation link is invalid.')}
          />
        ) : invitation ? (
          <>
            <p className="auth-eyebrow">Workspace invitation</p>
            <h1 className="auth-title">Join {invitation.workspaceName}</h1>
            <div className="invitation-summary">
              <span>Invited account</span>
              <strong>{invitation.email}</strong>
              <span>Workspace role</span>
              <strong>{titleCase(invitation.role)}</strong>
              <span>Expires</span>
              <strong>{formatDateTime(invitation.expiresAt)}</strong>
            </div>

            {invitation.status !== 'PENDING' ? (
              <InvitationState
                icon={<CheckCircle2 />}
                title={invitationStatusTitle(invitation.status)}
                body={invitationStatusBody(invitation.status)}
              />
            ) : !isAuthenticated ? (
              <div className="invitation-actions">
                <Button asChild>
                  <Link to={`/login?returnTo=${encodeURIComponent(returnTo)}`}>
                    <ArrowRight aria-hidden="true" />
                    Sign in to join
                  </Link>
                </Button>
                <Button variant="outline" asChild>
                  <Link
                    to={`/register?invitation=${encodeURIComponent(token)}&returnTo=${encodeURIComponent(returnTo)}`}
                  >
                    Create account
                  </Link>
                </Button>
              </div>
            ) : sessionQuery.isLoading ? (
              <InvitationState icon={<Loader2 className="auth-spin" />} title="Checking account" />
            ) : isMatchingAccount ? (
              <div className="invitation-actions">
                {acceptMutation.error ? (
                  <p className="auth-error">
                    {errorMessage(acceptMutation.error, 'Unable to join this workspace.')}
                  </p>
                ) : null}
                <Button
                  type="button"
                  onClick={() => acceptMutation.mutate()}
                  disabled={acceptMutation.isPending}
                >
                  {acceptMutation.isPending ? (
                    <Loader2 aria-hidden="true" className="auth-spin" />
                  ) : (
                    <ArrowRight aria-hidden="true" />
                  )}
                  Join workspace
                </Button>
              </div>
            ) : (
              <div className="invitation-account-mismatch">
                <p>
                  This invitation is for <strong>{invitation.email}</strong>, but you are signed in
                  as <strong>{session?.user.email ?? 'another account'}</strong>.
                </p>
                <Button type="button" variant="outline" onClick={() => void switchAccount()}>
                  <LogOut aria-hidden="true" />
                  Switch account
                </Button>
              </div>
            )}
          </>
        ) : null}
      </section>
    </main>
  )
}

function InvitationState({
  icon,
  title,
  body,
}: {
  icon: ReactNode
  title: string
  body?: string
}) {
  return (
    <div className="invitation-state">
      <span aria-hidden="true">{icon}</span>
      <strong>{title}</strong>
      {body ? <p>{body}</p> : null}
    </div>
  )
}

function invitationStatusTitle(status: string) {
  if (status === 'EXPIRED') return 'Invitation expired'
  if (status === 'REVOKED') return 'Invitation revoked'
  return 'Invitation already accepted'
}

function invitationStatusBody(status: string) {
  if (status === 'EXPIRED') return 'Ask a workspace administrator to generate a new link.'
  if (status === 'REVOKED') return 'This invitation can no longer be used.'
  return 'Sign in to FlowAI to open the workspace if you are already a member.'
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function titleCase(value: string) {
  return value.charAt(0) + value.slice(1).toLowerCase()
}

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError || error instanceof Error) {
    return error.message
  }
  return fallback
}
