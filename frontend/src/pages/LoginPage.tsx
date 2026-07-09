import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate } from 'react-router'
import { z } from 'zod'
import { ArrowRight, Loader2 } from 'lucide-react'
import { ApiError } from '@/api/client'
import { login } from '@/auth/auth-api'
import { saveAuthTokens } from '@/auth/token-storage'
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
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: {
      email: 'demo@flowai.local',
      password: 'flowai-demo-password',
    },
  })

  if (isAuthenticated) {
    return <Navigate to="/app" replace />
  }

  async function submitLoginForm(values: LoginFormValues) {
    setError(null)
    setIsSubmitting(true)

    try {
      const response = await login({
        email: values.email.trim(),
        password: values.password,
      })
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
