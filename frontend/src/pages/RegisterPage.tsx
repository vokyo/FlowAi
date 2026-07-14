import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { ArrowRight, Loader2 } from 'lucide-react'
import { ApiError } from '@/api/client'
import { register, registerWithInvitation } from '@/auth/auth-api'
import { safeAuthReturnTo } from '@/auth/auth-navigation'
import { setAccessToken } from '@/auth/access-token'
import { Button } from '@/components/ui/button'
import { getWorkspaceInvitationPreview } from '@/workspace/workspace-api'

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
  const [searchParams] = useSearchParams()
  const invitationToken = searchParams.get('invitation') ?? ''
  const returnTo = safeAuthReturnTo(searchParams.get('returnTo'))
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const {
    register: registerField,
    handleSubmit,
    formState: { errors },
    setValue,
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerFormSchema),
    defaultValues: {
      displayName: '',
      email: '',
      password: '',
      workspaceName: 'FlowAI Workspace',
    },
  })

  const invitationQuery = useQuery({
    queryKey: ['workspace-invitation-preview', invitationToken],
    queryFn: () => getWorkspaceInvitationPreview(invitationToken),
    enabled: Boolean(invitationToken),
    retry: false,
  })

  useEffect(() => {
    if (invitationQuery.data?.email) {
      setValue('email', invitationQuery.data.email, { shouldValidate: true })
    }
  }, [invitationQuery.data?.email, setValue])

  if (isAuthenticated) {
    return <Navigate to={returnTo} replace />
  }

  async function submitRegisterForm(values: RegisterFormValues) {
    setError(null)
    setIsSubmitting(true)

    try {
      const response = invitationToken
        ? await registerWithInvitation({
            token: invitationToken,
            displayName: values.displayName.trim(),
            email: values.email.trim(),
            password: values.password,
          })
        : await register({
            displayName: values.displayName.trim(),
            email: values.email.trim(),
            password: values.password,
            workspaceName: values.workspaceName.trim(),
          })
      setAccessToken(response.accessToken)
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
        <h1 className="auth-title">
          {invitationToken ? `Join ${invitationQuery.data?.workspaceName ?? 'workspace'}` : 'Create account'}
        </h1>
        {invitationQuery.error ? (
          <p className="auth-error">
            {getAuthErrorMessage(invitationQuery.error, 'Unable to load invitation.')}
          </p>
        ) : null}
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
              readOnly={Boolean(invitationQuery.data?.email)}
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
          {!invitationToken ? (
            <>
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
            </>
          ) : null}
          {error ? <p className="auth-error">{error}</p> : null}
          <Button
            className="auth-submit"
            disabled={
              isSubmitting ||
              invitationQuery.isLoading ||
              Boolean(invitationToken && invitationQuery.data?.status !== 'PENDING')
            }
            type="submit"
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <ArrowRight aria-hidden="true" />
            )}
            {invitationToken ? 'Create account and join' : 'Create account'}
          </Button>
        </form>
        <p className="auth-switch">
          Already have an account?{' '}
          <Link
            to={
              invitationToken
                ? `/login?returnTo=${encodeURIComponent(returnTo)}`
                : '/login'
            }
          >
            Sign in
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
