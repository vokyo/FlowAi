import { useQuery } from '@tanstack/react-query'
import { LogOut } from 'lucide-react'
import { ApiError } from '@/api/client'
import { getCurrentUser } from '@/auth/auth-api'
import { Button } from '@/components/ui/button'

type AppPageProps = {
  onSignOut: () => void
}

export function AppPage({ onSignOut }: AppPageProps) {
  const currentUserQuery = useQuery({
    queryKey: ['current-user'],
    queryFn: getCurrentUser,
    retry: false,
  })

  const currentUser = currentUserQuery.data

  return (
    <main className="app-shell">
      <aside className="app-sidebar">
        <div>
          <p className="app-logo">FlowAI</p>
          <p className="app-muted">Workspace</p>
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
          {currentUserQuery.isLoading ? (
            <p className="app-state">Loading your workspace.</p>
          ) : null}
          {currentUserQuery.isError ? (
            <p className="app-error">
              {getErrorMessage(currentUserQuery.error)}
            </p>
          ) : null}
          {currentUser ? (
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
                <dd>{currentUser.currentWorkspaceId ?? 'Pending backend'}</dd>
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
