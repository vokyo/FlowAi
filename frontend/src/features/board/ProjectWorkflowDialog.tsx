import { useEffect, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import { ArrowDown, ArrowUp, Check, Loader2, Pencil, Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { Project, ProjectWorkflowState } from '@/work/work-api'
import {
  WORKFLOW_STATE_CATEGORIES,
  createProjectWorkflowStateFormSchema,
  updateProjectWorkflowStateFormSchema,
  type CreateProjectWorkflowStateFormValues,
  type UpdateProjectWorkflowStateFormValues,
} from '@/features/project-shell/project-model'
import { formatStatus, toWorkflowStateCategoryInput } from '@/features/project-shell/display-utils'
import {
  ErrorState,
  InlineNotice,
  InlineState,
  ModalShell,
  StatusIcon,
} from '@/features/project-shell/feature-ui'

export function ProjectWorkflowDialog({
  isOpen,
  selectedProject,
  workflowStates,
  onSubmit,
  onUpdate,
  onReorder,
  onClose,
  canCreateWorkflowStates,
  isLoadingWorkflowStates,
  workflowStatesError,
  isSubmitting,
  isUpdating,
  isReordering,
  error,
}: {
  isOpen: boolean
  selectedProject: Project | null
  workflowStates: ProjectWorkflowState[]
  onSubmit: (values: CreateProjectWorkflowStateFormValues) => Promise<ProjectWorkflowState | null>
  onUpdate: (
    workflowStateId: string,
    values: UpdateProjectWorkflowStateFormValues,
  ) => Promise<ProjectWorkflowState | null>
  onReorder: (workflowStateId: string, direction: -1 | 1) => Promise<void>
  onClose: () => void
  canCreateWorkflowStates: boolean
  isLoadingWorkflowStates: boolean
  workflowStatesError: Error | null
  isSubmitting: boolean
  isUpdating: boolean
  isReordering: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<CreateProjectWorkflowStateFormValues>({
    resolver: zodResolver(createProjectWorkflowStateFormSchema),
    defaultValues: {
      name: '',
      category: 'IN_PROGRESS',
    },
  })
  const workflowStateName = useWatch({ control, name: 'name' }) ?? ''
  const isMutatingWorkflowState = isSubmitting || isUpdating || isReordering

  useEffect(() => {
    if (isOpen) {
      reset({
        name: '',
        category: 'IN_PROGRESS',
      })
    }
  }, [isOpen, reset])

  async function submitWorkflowState(values: CreateProjectWorkflowStateFormValues) {
    try {
      await onSubmit(values)
      reset({
        name: '',
        category: 'IN_PROGRESS',
      })
    } catch {
      // The mutation error is rendered below so the draft status stays available.
    }
  }

  if (!isOpen) {
    return null
  }

  return (
    <ModalShell
      title="Workflow statuses"
      eyebrow={selectedProject?.name ?? 'Project'}
      onClose={onClose}
      variant="members"
      isCloseDisabled={isMutatingWorkflowState}
    >
      <div className="project-members-dialog">
        {isLoadingWorkflowStates ? <InlineState>Loading workflow statuses.</InlineState> : null}
        {workflowStatesError ? <ErrorState error={workflowStatesError} /> : null}
        {!isLoadingWorkflowStates && !workflowStatesError ? (
          <div className="project-member-list" aria-label="Workflow statuses">
            {workflowStates.map((workflowState, index) => (
              <WorkflowStateRow
                key={workflowState.id}
                workflowState={workflowState}
                canManageWorkflowStates={canCreateWorkflowStates}
                canMoveUp={index > 0}
                canMoveDown={index < workflowStates.length - 1}
                isMutating={isMutatingWorkflowState}
                onUpdate={onUpdate}
                onReorder={onReorder}
              />
            ))}
          </div>
        ) : null}

        <section className="project-member-add-section">
          <div className="project-member-add-heading">
            <h3>Add status</h3>
            <span className="app-state">{workflowStates.length} statuses</span>
          </div>
          {!canCreateWorkflowStates ? (
            <InlineNotice>Only project owners can manage workflow statuses.</InlineNotice>
          ) : (
            <form
              className="project-member-add-form"
              onSubmit={handleSubmit(submitWorkflowState)}
              noValidate
            >
              <label className="app-field">
                Name
                <input
                  placeholder="Review"
                  disabled={isMutatingWorkflowState}
                  {...register('name')}
                />
              </label>
              <label className="app-field">
                Category
                <select disabled={isMutatingWorkflowState} {...register('category')}>
                  {WORKFLOW_STATE_CATEGORIES.map((category) => (
                    <option key={category} value={category}>
                      {formatStatus(category)}
                    </option>
                  ))}
                </select>
              </label>
              {errors.name?.message ? (
                <InlineNotice tone="warning">{errors.name.message}</InlineNotice>
              ) : null}
              {errors.category?.message ? (
                <InlineNotice tone="warning">{errors.category.message}</InlineNotice>
              ) : null}
              <Button type="submit" disabled={!workflowStateName.trim() || isMutatingWorkflowState}>
                {isSubmitting ? (
                  <Loader2 aria-hidden="true" className="auth-spin" />
                ) : (
                  <Plus aria-hidden="true" />
                )}
                Add status
              </Button>
            </form>
          )}
          {error ? <ErrorState error={error} /> : null}
        </section>
      </div>
    </ModalShell>
  )
}

function WorkflowStateRow({
  workflowState,
  canManageWorkflowStates,
  canMoveUp,
  canMoveDown,
  isMutating,
  onUpdate,
  onReorder,
}: {
  workflowState: ProjectWorkflowState
  canManageWorkflowStates: boolean
  canMoveUp: boolean
  canMoveDown: boolean
  isMutating: boolean
  onUpdate: (
    workflowStateId: string,
    values: UpdateProjectWorkflowStateFormValues,
  ) => Promise<ProjectWorkflowState | null>
  onReorder: (workflowStateId: string, direction: -1 | 1) => Promise<void>
}) {
  const [isEditing, setIsEditing] = useState(false)
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<UpdateProjectWorkflowStateFormValues>({
    resolver: zodResolver(updateProjectWorkflowStateFormSchema),
    defaultValues: {
      name: workflowState.name,
      category: toWorkflowStateCategoryInput(workflowState.category),
    },
  })

  useEffect(() => {
    reset({
      name: workflowState.name,
      category: toWorkflowStateCategoryInput(workflowState.category),
    })
  }, [reset, workflowState.category, workflowState.name])

  async function submitWorkflowState(values: UpdateProjectWorkflowStateFormValues) {
    try {
      await onUpdate(workflowState.id, values)
      setIsEditing(false)
    } catch {
      // The mutation error is rendered in the dialog footer so the edited row stays open.
    }
  }

  function cancelEdit() {
    reset({
      name: workflowState.name,
      category: toWorkflowStateCategoryInput(workflowState.category),
    })
    setIsEditing(false)
  }

  return (
    <div className="project-member-row project-workflow-row">
      <span className="workspace-avatar project-member-avatar" aria-hidden="true">
        <StatusIcon status={workflowState.category} />
      </span>
      {isEditing ? (
        <form
          className="workflow-state-edit-form"
          onSubmit={handleSubmit(submitWorkflowState)}
          noValidate
        >
          <label className="app-field">
            Name
            <input disabled={isMutating} {...register('name')} />
          </label>
          <label className="app-field">
            Category
            <select disabled={isMutating} {...register('category')}>
              {WORKFLOW_STATE_CATEGORIES.map((category) => (
                <option key={category} value={category}>
                  {formatStatus(category)}
                </option>
              ))}
            </select>
          </label>
          {errors.name?.message ? (
            <InlineNotice tone="warning">{errors.name.message}</InlineNotice>
          ) : null}
          {errors.category?.message ? (
            <InlineNotice tone="warning">{errors.category.message}</InlineNotice>
          ) : null}
          <span className="workflow-state-edit-actions">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={cancelEdit}
              disabled={isMutating}
              aria-label="Cancel editing status"
              title="Cancel"
            >
              <X aria-hidden="true" />
            </Button>
            <Button
              type="submit"
              size="icon-sm"
              disabled={!isDirty || isMutating}
              aria-label="Save workflow status"
              title="Save"
            >
              {isMutating ? (
                <Loader2 aria-hidden="true" className="auth-spin" />
              ) : (
                <Check aria-hidden="true" />
              )}
            </Button>
          </span>
        </form>
      ) : (
        <>
          <span className="project-member-person">
            <strong>{workflowState.name}</strong>
            <small>{formatStatus(workflowState.category)}</small>
          </span>
          <span className="project-member-meta workflow-state-actions">
            <span className="project-member-status">#{workflowState.position}</span>
            {canManageWorkflowStates ? (
              <>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => void onReorder(workflowState.id, -1)}
                  disabled={!canMoveUp || isMutating}
                  aria-label={`Move ${workflowState.name} up`}
                  title="Move up"
                >
                  <ArrowUp aria-hidden="true" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => void onReorder(workflowState.id, 1)}
                  disabled={!canMoveDown || isMutating}
                  aria-label={`Move ${workflowState.name} down`}
                  title="Move down"
                >
                  <ArrowDown aria-hidden="true" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => setIsEditing(true)}
                  disabled={isMutating}
                  aria-label={`Edit ${workflowState.name}`}
                  title="Edit"
                >
                  <Pencil aria-hidden="true" />
                </Button>
              </>
            ) : null}
          </span>
        </>
      )}
    </div>
  )
}
