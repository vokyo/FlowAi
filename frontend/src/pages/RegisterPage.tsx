import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { ArrowRight, Loader2 } from 'lucide-react'
import { ApiError } from '@/api/client'
import { register } from '@/auth/auth-api'
import { saveAuthTokens } from '@/auth/token-storage'
import { Button } from '@/components/ui/button'

type RegisterPageProps = {
  isAuthenticated: boolean
  onAuthenticated: () => void
}

export function RegisterPage({
  isAuthenticated,
  onAuthenticated,
}: RegisterPageProps) {
  const navigate = useNavigate()
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [workspaceName, setWorkspaceName] = useState('FlowAI Workspace')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  if (isAuthenticated) {
    return <Navigate to="/app" replace />
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setIsSubmitting(true)

    try {
      const response = await register({
        displayName,
        email,
        password,
        workspaceName,
      })
      saveAuthTokens(response)
      onAuthenticated()
      navigate('/app', { replace: true })
    } catch (caughtError) {
      setError(getAuthErrorMessage(caughtError, 'Unable to create account.'))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="auth-screen">
      <section className="auth-panel auth-panel-wide">
        <p className="auth-eyebrow">FlowAI</p>
        <h1 className="auth-title">Create account</h1>
        <form className="auth-form" onSubmit={handleSubmit}>
          <label className="auth-field">
            <span>Name</span>
            <input
              autoComplete="name"
              name="displayName"
              onChange={(event) => setDisplayName(event.target.value)}
              required
              type="text"
              value={displayName}
            />
          </label>
          <label className="auth-field">
            <span>Email</span>
            <input
              autoComplete="email"
              name="email"
              onChange={(event) => setEmail(event.target.value)}
              required
              type="email"
              value={email}
            />
          </label>
          <label className="auth-field">
            <span>Password</span>
            <input
              autoComplete="new-password"
              minLength={8}
              name="password"
              onChange={(event) => setPassword(event.target.value)}
              required
              type="password"
              value={password}
            />
          </label>
          <label className="auth-field">
            <span>Workspace</span>
            <input
              name="workspaceName"
              onChange={(event) => setWorkspaceName(event.target.value)}
              required
              type="text"
              value={workspaceName}
            />
          </label>
          {error ? <p className="auth-error">{error}</p> : null}
          <Button className="auth-submit" disabled={isSubmitting} type="submit">
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <ArrowRight aria-hidden="true" />
            )}
            Create account
          </Button>
        </form>
        <p className="auth-switch">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </section>
    </main>
  )
}

function getAuthErrorMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    return error.message
  }

  return fallback
}
