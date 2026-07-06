import { useQuery } from '@tanstack/react-query'
import { LogOut } from 'lucide-react'
import { ApiError } from '@/api/client'
import { getCurrentSession } from '@/auth/auth-api'
import { Button } from '@/components/ui/button'

type AppPageProps = {
  onSignOut: () => void
}

export function AppPage({ onSignOut }: AppPageProps) {
  const currentSessionQuery = useQuery({
    queryKey: ['current-session'],
    queryFn: getCurrentSession,
    retry: false,
  })

  const currentSession = currentSessionQuery.data
  const currentUser = currentSession?.user
  const currentWorkspace = currentSession?.workspace

  return (
    <main className="app-shell">
      <aside className="app-sidebar">
        <div>
          <p className="app-logo">FlowAI</p>
          <p className="app-muted">{currentWorkspace?.name ?? 'Workspace'}</p>
        </div>
        <Button type="button" variant="outline" onClick={onSignOut}>
          <LogOut aria-hidden="true" />
          Sign out
        </Button>
      </aside>
      <section className="app-main">
        <div className="app-toolbar">
          <div>
            <p className="app-muted">Signed in</p>
            <h1 className="app-title">
              {currentUser?.displayName ?? currentUser?.email ?? 'Loading'}
            </h1>
          </div>
        </div>
        <div className="app-content">
          {currentSessionQuery.isLoading ? (
            <p className="app-state">Loading your workspace.</p>
          ) : null}
          {currentSessionQuery.isError ? (
            <p className="app-error">
              {getErrorMessage(currentSessionQuery.error)}
            </p>
          ) : null}
          {currentUser && currentWorkspace ? (
            <dl className="app-details">
              <div>
                <dt>Email</dt>
                <dd>{currentUser.email}</dd>
              </div>
              <div>
                <dt>User ID</dt>
                <dd>{currentUser.id}</dd>
              </div>
              <div>
                <dt>Workspace ID</dt>
                <dd>{currentWorkspace.id}</dd>
              </div>
              <div>
                <dt>Workspace</dt>
                <dd>{currentWorkspace.name}</dd>
              </div>
              <div>
                <dt>Role</dt>
                <dd>{currentWorkspace.role ?? 'MEMBER'}</dd>
              </div>
            </dl>
          ) : null}
        </div>
      </section>
    </main>
  )
}

function getErrorMessage(error: Error) {
  if (error instanceof ApiError) {
    return error.message
  }

  return 'Unable to load your session.'
}
