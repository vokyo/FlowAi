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
      navigate('/login', { replace: true })
    })

    return () => {
      clearAccessTokenProvider()
      clearUnauthorizedHandler()
    }
  }, [navigate])

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
        path="/app"
        element={
          <RequireAuth sessionVersion={sessionVersion}>
            <AppPage onSignOut={signOut} />
          </RequireAuth>
        }
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
