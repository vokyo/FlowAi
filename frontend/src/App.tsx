import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Navigate, Route, Routes, useNavigate } from 'react-router'
import {
  clearAccessTokenProvider,
  clearUnauthorizedHandler,
  refreshAccessToken,
  setAccessTokenProvider,
  setUnauthorizedHandler,
} from '@/api/client'
import {
  getAccessToken,
  hasAccessToken,
} from '@/auth/access-token'
import { logout } from '@/auth/auth-api'
import { clearClientSession } from '@/auth/client-session'
import { LoginPage } from '@/pages/LoginPage'
import { RegisterPage } from '@/pages/RegisterPage'
import './App.css'

const AppPage = lazy(() => import('@/pages/AppPage').then((module) => ({ default: module.AppPage })))
const InvitationPage = lazy(() => import('@/pages/InvitationPage').then((module) => ({ default: module.InvitationPage })))
const SettingsPage = lazy(() => import('@/pages/SettingsPage').then((module) => ({ default: module.SettingsPage })))

function App() {
  return <AppRoutes />
}

function AppRoutes() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const navigateRef = useRef(navigate)
  const [sessionVersion, setSessionVersion] = useState(0)
  const [isRestoringSession, setIsRestoringSession] = useState(true)

  useEffect(() => {
    navigateRef.current = navigate
  }, [navigate])

  function refreshSession() {
    setSessionVersion((version) => version + 1)
  }

  async function signOut() {
    try {
      await logout()
    } catch {
      // Local sign-out must still complete when the server is unavailable.
    }
    clearClientSession(queryClient)
    refreshSession()
    navigate('/login', { replace: true })
  }

  useEffect(() => {
    let isActive = true

    setAccessTokenProvider(getAccessToken)
    setUnauthorizedHandler(() => {
      clearClientSession(queryClient)
      refreshSession()
      const currentPath = window.location.pathname
      const loginPath = currentPath.startsWith('/invite/')
        ? `/login?returnTo=${encodeURIComponent(currentPath)}`
        : '/login'
      navigateRef.current(loginPath, { replace: true })
    })

    void refreshAccessToken().finally(() => {
      if (isActive) {
        setIsRestoringSession(false)
        setSessionVersion((version) => version + 1)
      }
    })

    return () => {
      isActive = false
      clearAccessTokenProvider()
      clearUnauthorizedHandler()
    }
  }, [queryClient])

  if (isRestoringSession) {
    return <RouteLoading />
  }

  function renderAppPage() {
    return (
      <RequireAuth sessionVersion={sessionVersion}>
        <Suspense fallback={<RouteLoading />}>
          <AppPage key={sessionVersion} onSignOut={() => void signOut()} onSessionChanged={refreshSession} />
        </Suspense>
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
        element={<Suspense fallback={<RouteLoading />}><InvitationPage onSessionChanged={refreshSession} /></Suspense>}
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
      <Route
        path="/app/settings"
        element={
          <RequireAuth sessionVersion={sessionVersion}>
            <Suspense fallback={<RouteLoading />}><SettingsPage onSessionChanged={refreshSession} /></Suspense>
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

function RouteLoading() {
  return <main className="route-loading" role="status" aria-label="Loading page"><span /></main>
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
