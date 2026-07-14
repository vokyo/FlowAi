import { useEffect } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import {
  CalendarDays,
  Flag,
  FolderKanban,
  Loader2,
  MoreHorizontal,
  Plus,
  Tag,
  UserCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { Project, ProjectLabel, ProjectMember, ProjectWorkflowState } from '@/work/work-api'
import {
  DEFAULT_LABEL_COLOR,
  ISSUE_PRIORITIES,
  PRIORITY_LABELS,
  createIssueFormSchema,
  createProjectLabelFormSchema,
  type CreateIssueFormValues,
  type CreateProjectLabelFormValues,
} from '@/features/project-shell/project-model'
import {
  ErrorState,
  InlineNotice,
  InlineState,
  LabelBadge,
  ModalShell,
  StatusIcon,
} from '@/features/project-shell/feature-ui'

export function CreateIssueDialog({
  isOpen,
  selectedProject,
  projectMembers,
  projectWorkflowStates,
  projectLabels,
  initialWorkflowStateId,
  initialTitle,
  initialAssigneeUserId,
  onCreateLabel,
  onSubmit,
  onClose,
  isSubmitting,
  isCreatingLabel,
  error,
  createLabelError,
}: {
  isOpen: boolean
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectWorkflowStates: ProjectWorkflowState[]
  projectLabels: ProjectLabel[]
  initialWorkflowStateId: string
  initialTitle: string
  initialAssigneeUserId: string
  onCreateLabel: (values: CreateProjectLabelFormValues) => Promise<ProjectLabel | null>
  onSubmit: (values: CreateIssueFormValues) => void
  onClose: () => void
  isSubmitting: boolean
  isCreatingLabel: boolean
  error: Error | null
  createLabelError: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    control,
    getValues,
    formState: { errors },
  } = useForm<CreateIssueFormValues>({
    resolver: zodResolver(createIssueFormSchema),
    defaultValues: {
      title: initialTitle,
      description: '',
      workflowStateId: initialWorkflowStateId,
      priority: '',
      labelIds: [],
      assigneeUserId: initialAssigneeUserId,
      dueDate: '',
    },
  })
  const {
    register: registerLabel,
    handleSubmit: handleLabelSubmit,
    reset: resetLabelForm,
    control: labelControl,
    formState: { errors: labelErrors },
  } = useForm<CreateProjectLabelFormValues>({
    resolver: zodResolver(createProjectLabelFormSchema),
    defaultValues: {
      name: '',
      color: DEFAULT_LABEL_COLOR,
    },
  })
  const issueTitle = useWatch({ control, name: 'title' }) ?? ''
  const issueWorkflowStateId = useWatch({ control, name: 'workflowStateId' }) ?? ''
  const issueLabelIds = useWatch({ control, name: 'labelIds' }) ?? []
  const newLabelName = useWatch({ control: labelControl, name: 'name' }) ?? ''
  const selectedWorkflowState = projectWorkflowStates.find(
    (workflowState) => workflowState.id === issueWorkflowStateId,
  )

  useEffect(() => {
    if (isOpen) {
      reset({
        title: initialTitle,
        description: '',
        workflowStateId: initialWorkflowStateId,
        priority: '',
        labelIds: [],
        assigneeUserId: initialAssigneeUserId,
        dueDate: '',
      })
      resetLabelForm({
        name: '',
        color: DEFAULT_LABEL_COLOR,
      })
    }
  }, [
    initialAssigneeUserId,
    initialTitle,
    initialWorkflowStateId,
    isOpen,
    reset,
    resetLabelForm,
  ])

  function handleIssueLabelToggle(labelId: string) {
    const nextLabelIds = issueLabelIds.includes(labelId)
      ? issueLabelIds.filter((currentLabelId) => currentLabelId !== labelId)
      : [...issueLabelIds, labelId]

    setValue('labelIds', nextLabelIds, {
      shouldDirty: true,
      shouldTouch: true,
      shouldValidate: true,
    })
  }

  async function submitNewLabel(values: CreateProjectLabelFormValues) {
    try {
      const label = await onCreateLabel(values)
      if (!label) {
        return
      }

      const currentLabelIds = getValues('labelIds') ?? []
      setValue(
        'labelIds',
        currentLabelIds.includes(label.id) ? currentLabelIds : [...currentLabelIds, label.id],
        {
          shouldDirty: true,
          shouldTouch: true,
          shouldValidate: true,
        },
      )
      resetLabelForm({
        name: '',
        color: DEFAULT_LABEL_COLOR,
      })
    } catch {
      // The create-label mutation error is rendered in the label section.
    }
  }

  if (!isOpen) {
    return null
  }

  return (
    <ModalShell
      title="New issue"
      eyebrow={selectedProject?.name ?? 'No project selected'}
      onClose={onClose}
      variant="issue"
    >
      <form
        className="issue-modal-form"
        onSubmit={handleSubmit((values) => onSubmit(values))}
        noValidate
      >
        <input
          autoFocus
          className="issue-modal-title"
          placeholder="Issue title"
          disabled={!selectedProject}
          {...register('title')}
        />
        {errors.title?.message ? <InlineNotice tone="warning">{errors.title.message}</InlineNotice> : null}
        <textarea
          className="issue-modal-description"
          placeholder="Add description..."
          rows={5}
          disabled={!selectedProject}
          {...register('description')}
        />
        {errors.description?.message ? (
          <InlineNotice tone="warning">{errors.description.message}</InlineNotice>
        ) : null}
        <div className="issue-modal-chip-row" aria-label="Issue properties">
          <label className="issue-modal-chip issue-modal-chip-select">
            <StatusIcon status={selectedWorkflowState?.category ?? 'TODO'} />
            <select
              disabled={!selectedProject || projectWorkflowStates.length === 0}
              aria-label="Status"
              {...register('workflowStateId')}
            >
              {projectWorkflowStates.map((workflowState) => (
                <option key={workflowState.id} value={workflowState.id}>
                  {workflowState.name}
                </option>
              ))}
            </select>
          </label>
          <label className="issue-modal-chip issue-modal-chip-select">
            <Flag aria-hidden="true" />
            <select
              disabled={!selectedProject}
              aria-label="Priority"
              {...register('priority')}
            >
              <option value="">Priority</option>
              {ISSUE_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {PRIORITY_LABELS[priority]}
                </option>
              ))}
            </select>
          </label>
          <label className="issue-modal-chip issue-modal-chip-select">
            <UserCircle aria-hidden="true" />
            <select
              disabled={!selectedProject || projectMembers.length === 0}
              aria-label="Assignee"
              {...register('assigneeUserId')}
            >
              <option value="">Assignee</option>
              {projectMembers.map((member) => (
                <option key={member.id} value={member.userId}>
                  {member.displayName || member.email}
                </option>
              ))}
            </select>
          </label>
          <label className="issue-modal-chip issue-modal-chip-date">
            <CalendarDays aria-hidden="true" />
            <input
              type="date"
              disabled={!selectedProject}
              aria-label="Due date"
              {...register('dueDate')}
            />
          </label>
          <span className="issue-modal-chip">
            <FolderKanban aria-hidden="true" />
            {selectedProject?.name ?? 'Project'}
          </span>
          <span className="issue-modal-chip issue-modal-chip-muted">
            <MoreHorizontal aria-hidden="true" />
          </span>
        </div>
        <div className="issue-modal-label-section">
          <div className="issue-modal-section-title">
            <Tag aria-hidden="true" />
            Labels
          </div>
          {projectLabels.length === 0 ? (
            <InlineState>No labels yet.</InlineState>
          ) : (
            <div className="label-toggle-list label-toggle-list-inline">
              {projectLabels.map((label) => (
                <label className="label-toggle" key={label.id}>
                  <input
                    type="checkbox"
                    checked={issueLabelIds.includes(label.id)}
                    onChange={() => handleIssueLabelToggle(label.id)}
                    disabled={!selectedProject || isSubmitting}
                  />
                  <LabelBadge label={label} />
                </label>
              ))}
            </div>
          )}
          <div className="issue-modal-label-create">
            <label className="app-field">
              New label
              <input
                placeholder="Bug"
                disabled={!selectedProject || isCreatingLabel}
                {...registerLabel('name')}
              />
            </label>
            <label className="app-field app-field-color">
              Color
              <input
                type="color"
                disabled={!selectedProject || isCreatingLabel}
                {...registerLabel('color')}
              />
            </label>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                void handleLabelSubmit(submitNewLabel)()
              }}
              disabled={!selectedProject || !newLabelName.trim() || isCreatingLabel}
            >
              {isCreatingLabel ? (
                <Loader2 aria-hidden="true" className="auth-spin" />
              ) : (
                <Plus aria-hidden="true" />
              )}
              Add label
            </Button>
          </div>
          {labelErrors.name?.message ? (
            <InlineNotice tone="warning">{labelErrors.name.message}</InlineNotice>
          ) : null}
          {labelErrors.color?.message ? (
            <InlineNotice tone="warning">{labelErrors.color.message}</InlineNotice>
          ) : null}
          {createLabelError ? <ErrorState error={createLabelError} /> : null}
        </div>
        {error ? <ErrorState error={error} /> : null}
        <div className="issue-modal-footer">
          <span className="app-state">{selectedProject?.name ?? 'No project selected'}</span>
          <Button
            type="submit"
            disabled={!selectedProject || !issueTitle.trim() || isSubmitting}
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            Create issue
          </Button>
        </div>
      </form>
    </ModalShell>
  )
}
