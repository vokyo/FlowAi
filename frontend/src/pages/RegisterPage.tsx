import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate } from 'react-router'
import { z } from 'zod'
import { ArrowRight, Loader2 } from 'lucide-react'
import { ApiError } from '@/api/client'
import { register } from '@/auth/auth-api'
import { saveAuthTokens } from '@/auth/token-storage'
import { Button } from '@/components/ui/button'

type RegisterPageProps = {
  isAuthenticated: boolean
  onAuthenticated: () => void
}

const registerFormSchema = z.object({
  displayName: z.string().refine((value) => value.trim().length > 0, {
    message: 'Name is required.',
  }),
  email: z.string().min(1, 'Email is required.').email('Enter a valid email address.'),
  password: z.string().min(8, 'Password must be at least 8 characters.'),
  workspaceName: z.string().refine((value) => value.trim().length > 0, {
    message: 'Workspace is required.',
  }),
})

type RegisterFormValues = z.infer<typeof registerFormSchema>

export function RegisterPage({
  isAuthenticated,
  onAuthenticated,
}: RegisterPageProps) {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const {
    register: registerField,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerFormSchema),
    defaultValues: {
      displayName: '',
      email: '',
      password: '',
      workspaceName: 'FlowAI Workspace',
    },
  })

  if (isAuthenticated) {
    return <Navigate to="/app" replace />
  }

  async function submitRegisterForm(values: RegisterFormValues) {
    setError(null)
    setIsSubmitting(true)

    try {
      const response = await register({
        displayName: values.displayName.trim(),
        email: values.email.trim(),
        password: values.password,
        workspaceName: values.workspaceName.trim(),
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
        <form className="auth-form" onSubmit={handleSubmit(submitRegisterForm)} noValidate>
          <label className="auth-field">
            <span>Name</span>
            <input
              autoComplete="name"
              type="text"
              {...registerField('displayName')}
            />
          </label>
          {errors.displayName?.message ? (
            <p className="auth-error">{errors.displayName.message}</p>
          ) : null}
          <label className="auth-field">
            <span>Email</span>
            <input
              autoComplete="email"
              type="email"
              {...registerField('email')}
            />
          </label>
          {errors.email?.message ? <p className="auth-error">{errors.email.message}</p> : null}
          <label className="auth-field">
            <span>Password</span>
            <input
              autoComplete="new-password"
              type="password"
              {...registerField('password')}
            />
          </label>
          {errors.password?.message ? (
            <p className="auth-error">{errors.password.message}</p>
          ) : null}
          <label className="auth-field">
            <span>Workspace</span>
            <input
              type="text"
              {...registerField('workspaceName')}
            />
          </label>
          {errors.workspaceName?.message ? (
            <p className="auth-error">{errors.workspaceName.message}</p>
          ) : null}
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
