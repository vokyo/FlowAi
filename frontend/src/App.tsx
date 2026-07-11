import { useEffect, useState } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router'
import {
  clearAccessTokenProvider,
  clearUnauthorizedHandler,
  setAccessTokenProvider,
  setUnauthorizedHandler,
} from '@/api/client'
import {
  clearAuthTokens,
  getAccessToken,
  hasAccessToken,
} from '@/auth/token-storage'
import { AppPage } from '@/pages/AppPage'
import { LoginPage } from '@/pages/LoginPage'
import { RegisterPage } from '@/pages/RegisterPage'
import { InvitationPage } from '@/pages/InvitationPage'
import './App.css'

function App() {
  return <AppRoutes />
}

function AppRoutes() {
  const navigate = useNavigate()
  const [sessionVersion, setSessionVersion] = useState(0)

  function refreshSession() {
    setSessionVersion((version) => version + 1)
  }

  function signOut() {
    clearAuthTokens()
    refreshSession()
    navigate('/login', { replace: true })
  }

  useEffect(() => {
    setAccessTokenProvider(getAccessToken)
    setUnauthorizedHandler(() => {
      clearAuthTokens()
      refreshSession()
      const currentPath = window.location.pathname
      const loginPath = currentPath.startsWith('/invite/')
        ? `/login?returnTo=${encodeURIComponent(currentPath)}`
        : '/login'
      navigate(loginPath, { replace: true })
    })

    return () => {
      clearAccessTokenProvider()
      clearUnauthorizedHandler()
    }
  }, [navigate])

  function renderAppPage() {
    return (
      <RequireAuth sessionVersion={sessionVersion}>
        <AppPage
          key={sessionVersion}
          onSignOut={signOut}
          onSessionChanged={refreshSession}
        />
      </RequireAuth>
    )
  }

  return (
    <Routes>
      <Route
        path="/"
        element={<Navigate to={hasAccessToken() ? '/app' : '/login'} replace />}
      />
      <Route
        path="/login"
        element={
          <LoginPage
            isAuthenticated={hasAccessToken()}
            onAuthenticated={refreshSession}
          />
        }
      />
      <Route
        path="/register"
        element={
          <RegisterPage
            isAuthenticated={hasAccessToken()}
            onAuthenticated={refreshSession}
          />
        }
      />
      <Route
        path="/invite/:token"
        element={<InvitationPage onSessionChanged={refreshSession} />}
      />
      <Route
        path="/app"
        element={renderAppPage()}
      />
      <Route
        path="/app/workspaces/:workspaceId/projects/:projectId"
        element={renderAppPage()}
      />
      <Route
        path="/app/workspaces/:workspaceId/projects/:projectId/analytics"
        element={renderAppPage()}
      />
      <Route
        path="/app/workspaces/:workspaceId/projects/:projectId/issues/:issueId"
        element={renderAppPage()}
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

function RequireAuth({
  children,
}: {
  children: React.ReactNode
  sessionVersion: number
}) {
  if (!hasAccessToken()) {
    return <Navigate to="/login" replace />
  }

  return children
}

export default App
