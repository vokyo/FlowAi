import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { useQuery } from '@tanstack/react-query'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { ArrowRight, Loader2, Sparkles } from 'lucide-react'
import { ApiError } from '@/api/client'
import { login } from '@/api/auth-api'
import { getDemoStatus } from '@/api/demo-api'
import { setAccessToken } from '@/auth/access-token'
import { invitationTokenFromReturnTo, safeAuthReturnTo } from '@/auth/auth-navigation'
import { Button } from '@/components/ui/button'

type LoginPageProps = {
  isAuthenticated: boolean
  onAuthenticated: () => void
}

const loginFormSchema = z.object({
  email: z.string().min(1, 'Email is required.').email('Enter a valid email address.'),
  password: z.string().min(1, 'Password is required.'),
})

type LoginFormValues = z.infer<typeof loginFormSchema>

export function LoginPage({
  isAuthenticated,
  onAuthenticated,
}: LoginPageProps) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const returnTo = safeAuthReturnTo(searchParams.get('returnTo'))
  const invitationToken = invitationTokenFromReturnTo(returnTo)
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isEnteringDemo, setIsEnteringDemo] = useState(false)
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: {
      email: '',
      password: '',
    },
  })

  // Only this deployment knows whether it was seeded, so the button appears from
  // the server's answer rather than a build-time constant. A failure here is not
  // worth surfacing: the visitor still has the normal sign-in form.
  const demoQuery = useQuery({
    queryKey: ['demo-status'],
    queryFn: getDemoStatus,
    retry: false,
    staleTime: Infinity,
  })
  const demo = demoQuery.data

  if (isAuthenticated) {
    return <Navigate to={returnTo} replace />
  }

  async function signIn(email: string, password: string) {
    const response = await login({ email, password })
    setAccessToken(response.accessToken)
    onAuthenticated()
    navigate(returnTo, { replace: true })
  }

  async function submitLoginForm(values: LoginFormValues) {
    setError(null)
    setIsSubmitting(true)

    try {
      await signIn(values.email.trim(), values.password)
    } catch (caughtError) {
      setError(getAuthErrorMessage(caughtError, 'Unable to sign in.'))
    } finally {
      setIsSubmitting(false)
    }
  }

  async function enterDemo() {
    if (!demo?.email || !demo.password) return
    setError(null)
    setIsEnteringDemo(true)

    try {
      await signIn(demo.email, demo.password)
    } catch (caughtError) {
      setError(getAuthErrorMessage(caughtError, 'Unable to open the demo workspace.'))
    } finally {
      setIsEnteringDemo(false)
    }
  }

  return (
    <main className="auth-screen">
      <section className="auth-panel">
        <p className="auth-eyebrow">FlowAI</p>
        <h1 className="auth-title">Sign in</h1>
        {demo?.enabled ? (
          <div className="auth-demo">
            <Button
              className="auth-demo-enter"
              type="button"
              disabled={isEnteringDemo || isSubmitting}
              onClick={() => void enterDemo()}
            >
              {isEnteringDemo ? (
                <Loader2 aria-hidden="true" className="auth-spin" />
              ) : (
                <Sparkles aria-hidden="true" />
              )}
              Explore the demo workspace
            </Button>
            <p className="auth-demo-note">
              A worked-in workspace with sample projects, issues, comments and
              several weeks of history. No sign-up needed.
            </p>
            <p className="auth-demo-divider">or sign in</p>
          </div>
        ) : null}
        <form className="auth-form" onSubmit={handleSubmit(submitLoginForm)} noValidate>
          <label className="auth-field">
            <span>Email</span>
            <input
              autoComplete="email"
              type="email"
              {...register('email')}
            />
          </label>
          {errors.email?.message ? <p className="auth-error">{errors.email.message}</p> : null}
          <label className="auth-field">
            <span>Password</span>
            <input
              autoComplete="current-password"
              type="password"
              {...register('password')}
            />
          </label>
          {errors.password?.message ? <p className="auth-error">{errors.password.message}</p> : null}
          {error ? <p className="auth-error">{error}</p> : null}
          {/*
            * One filled button per screen. Where the demo exists it is the call
            * to action, so signing in steps down to tonal; without it, signing
            * in is the only thing to do here and keeps the filled treatment.
            */}
          <Button
            className="auth-submit"
            variant={demo?.enabled ? 'tonal' : 'default'}
            disabled={isSubmitting || isEnteringDemo}
            type="submit"
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <ArrowRight aria-hidden="true" />
            )}
            Sign in
          </Button>
        </form>
        <p className="auth-switch">
          New to FlowAI?{' '}
          <Link
            to={
              invitationToken
                ? `/register?invitation=${encodeURIComponent(invitationToken)}&returnTo=${encodeURIComponent(returnTo)}`
                : '/register'
            }
          >
            Create an account
          </Link>
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
