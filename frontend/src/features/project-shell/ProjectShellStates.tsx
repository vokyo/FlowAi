import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import { Loader2, Plus } from 'lucide-react'
import type { AuthWorkspace } from '@/auth/auth-api'
import { Button } from '@/components/ui/button'
import {
  createProjectFormSchema,
  createWorkspaceFormSchema,
  type CreateProjectFormValues,
  type CreateWorkspaceFormValues,
} from './project-model'
import { EmptyState, ErrorState, InlineNotice, ModalShell } from './feature-ui'

export function WorkspaceMismatchState({
  currentWorkspace,
  routeWorkspaceId,
}: {
  currentWorkspace: AuthWorkspace | null
  routeWorkspaceId: string | undefined
}) {
  return (
    <div className="content-page">
      <EmptyState
        title="Workspace is not active"
        body={`This URL points to workspace ${routeWorkspaceId ?? 'unknown'}, but your current session is ${currentWorkspace?.name ?? 'another workspace'}. Return to /app to use the current workspace.`}
      />
    </div>
  )
}

export function CreateWorkspaceDialog({
  isOpen,
  onSubmit,
  onClose,
  isSubmitting,
  error,
}: {
  isOpen: boolean
  onSubmit: (values: CreateWorkspaceFormValues) => void
  onClose: () => void
  isSubmitting: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateWorkspaceFormValues>({
    resolver: zodResolver(createWorkspaceFormSchema),
    defaultValues: { name: '' },
  })

  useEffect(() => {
    if (isOpen) reset({ name: '' })
  }, [isOpen, reset])

  if (!isOpen) return null

  return (
    <ModalShell
      title="Create workspace"
      eyebrow="Workspace"
      onClose={onClose}
      isCloseDisabled={isSubmitting}
    >
      <form className="modal-form" onSubmit={handleSubmit(onSubmit)} noValidate>
        <label className="app-field">
          Name
          <input autoFocus placeholder="Product team" {...register('name')} />
        </label>
        {errors.name?.message ? (
          <InlineNotice tone="warning">{errors.name.message}</InlineNotice>
        ) : null}
        {error ? <ErrorState error={error} /> : null}
        <div className="modal-actions">
          <Button type="button" variant="ghost" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? <Loader2 className="auth-spin" /> : <Plus />}
            Create and switch
          </Button>
        </div>
      </form>
    </ModalShell>
  )
}

export function CreateProjectDialog({
  isOpen,
  onSubmit,
  onClose,
  canCreateProject,
  isSubmitting,
  error,
}: {
  isOpen: boolean
  onSubmit: (values: CreateProjectFormValues) => void
  onClose: () => void
  canCreateProject: boolean
  isSubmitting: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<CreateProjectFormValues>({
    resolver: zodResolver(createProjectFormSchema),
    defaultValues: {
      name: '',
      description: '',
    },
  })
  const projectName = useWatch({ control, name: 'name' }) ?? ''

  useEffect(() => {
    if (isOpen) {
      reset({
        name: '',
        description: '',
      })
    }
  }, [isOpen, reset])

  if (!isOpen) {
    return null
  }

  return (
    <ModalShell title="Create project" eyebrow="Project" onClose={onClose}>
      <form className="modal-form" onSubmit={handleSubmit(onSubmit)} noValidate>
        <label className="app-field">
          Name
          <input
            autoFocus
            placeholder="Mobile launch"
            disabled={!canCreateProject}
            {...register('name')}
          />
        </label>
        {errors.name?.message ? <InlineNotice tone="warning">{errors.name.message}</InlineNotice> : null}
        <label className="app-field">
          Description
          <textarea
            placeholder="Optional project notes"
            rows={4}
            disabled={!canCreateProject}
            {...register('description')}
          />
        </label>
        {errors.description?.message ? (
          <InlineNotice tone="warning">{errors.description.message}</InlineNotice>
        ) : null}
        {error ? <ErrorState error={error} /> : null}
        <div className="modal-actions">
          <Button type="button" variant="ghost" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={!canCreateProject || !projectName.trim() || isSubmitting}
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            Create project
          </Button>
        </div>
      </form>
    </ModalShell>
  )
}
import { useEffect } from 'react'
