import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { ArrowRight, Loader2 } from 'lucide-react'
import { ApiError } from '@/api/client'
import { login } from '@/auth/auth-api'
import { saveAuthTokens } from '@/auth/token-storage'
import { Button } from '@/components/ui/button'

type LoginPageProps = {
  isAuthenticated: boolean
  onAuthenticated: () => void
}

export function LoginPage({
  isAuthenticated,
  onAuthenticated,
}: LoginPageProps) {
  const navigate = useNavigate()
  const [email, setEmail] = useState('demo@flowai.local')
  const [password, setPassword] = useState('flowai-demo-password')
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
      const response = await login({ email, password })
      saveAuthTokens(response)
      onAuthenticated()
      navigate('/app', { replace: true })
    } catch (caughtError) {
      setError(getAuthErrorMessage(caughtError, 'Unable to sign in.'))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="auth-screen">
      <section className="auth-panel">
        <p className="auth-eyebrow">FlowAI</p>
        <h1 className="auth-title">Sign in</h1>
        <form className="auth-form" onSubmit={handleSubmit}>
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
              autoComplete="current-password"
              name="password"
              onChange={(event) => setPassword(event.target.value)}
              required
              type="password"
              value={password}
            />
          </label>
          {error ? <p className="auth-error">{error}</p> : null}
          <Button className="auth-submit" disabled={isSubmitting} type="submit">
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <ArrowRight aria-hidden="true" />
            )}
            Sign in
          </Button>
        </form>
        <p className="auth-switch">
          New to FlowAI? <Link to="/register">Create an account</Link>
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
