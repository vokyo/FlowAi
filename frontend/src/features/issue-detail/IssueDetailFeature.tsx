import { useEffect, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import {
  Activity,
  Archive,
  ArrowLeft,
  Check,
  Clock3,
  FolderKanban,
  Loader2,
  MessageSquare,
  PanelRight,
  Pencil,
  Send,
  UserCircle,
} from 'lucide-react'
import type { AuthUser, AuthWorkspace } from '@/auth/auth-api'
import { Button } from '@/components/ui/button'
import type {
  ActivityEvent,
  IssueComment,
  IssueDetail,
  IssuePriority,
  IssueSummary,
  Project,
  ProjectLabel,
  ProjectMember,
  ProjectWorkflowState,
  UpdateIssueRequest,
} from '@/work/work-api'
import {
  ISSUE_PRIORITIES,
  PRIORITY_LABELS,
  commentFormSchema,
  issueContentFormSchema,
  type CommentFormValues,
  type IssueContentFormValues,
} from '@/features/project-shell/project-model'
import { formatActivity, formatDate, getErrorMessage } from '@/features/project-shell/display-utils'
import {
  BreadcrumbLine,
  EmptyState,
  ErrorState,
  InlineNotice,
  InlineState,
  LabelBadge,
} from '@/features/project-shell/feature-ui'

export function IssueDetailFeature({
  issue,
  selectedProject,
  projectMembers,
  projectLabels,
  projectWorkflowStates,
  currentWorkspace,
  currentUser,
  comments,
  activities,
  isLoadingIssue,
  issueError,
  isLoadingComments,
  commentsError,
  hasMoreComments,
  isLoadingMoreComments,
  onLoadMoreComments,
  isLoadingActivities,
  activitiesError,
  hasMoreActivities,
  isLoadingMoreActivities,
  onLoadMoreActivities,
  onSubmitComment,
  isSubmittingComment,
  commentError,
  onBackToProject,
  onUpdateIssue,
  onArchiveIssue,
  isUpdatingIssue,
  updateIssueError,
  onResetUpdateIssueError,
}: {
  issue: IssueSummary | IssueDetail | null
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  projectWorkflowStates: ProjectWorkflowState[]
  currentWorkspace: AuthWorkspace | null
  currentUser: AuthUser | null
  comments: IssueComment[]
  activities: ActivityEvent[]
  isLoadingIssue: boolean
  issueError: Error | null
  isLoadingComments: boolean
  commentsError: Error | null
  hasMoreComments: boolean
  isLoadingMoreComments: boolean
  onLoadMoreComments: () => void
  isLoadingActivities: boolean
  activitiesError: Error | null
  hasMoreActivities: boolean
  isLoadingMoreActivities: boolean
  onLoadMoreActivities: () => void
  onSubmitComment: (values: CommentFormValues) => Promise<void>
  isSubmittingComment: boolean
  commentError: Error | null
  onBackToProject: () => void
  onUpdateIssue: (request: UpdateIssueRequest) => Promise<void>
  onArchiveIssue: () => Promise<void>
  isUpdatingIssue: boolean
  updateIssueError: Error | null
  onResetUpdateIssueError: () => void
}) {
  const [editingIssueId, setEditingIssueId] = useState<string | null>(null)
  const {
    register: registerIssueContent,
    handleSubmit: handleIssueContentSubmit,
    reset: resetIssueContentForm,
    control: issueContentControl,
    formState: { errors: issueContentErrors },
  } = useForm<IssueContentFormValues>({
    resolver: zodResolver(issueContentFormSchema),
    defaultValues: {
      title: issue?.title ?? '',
      description: issue?.description ?? '',
    },
  })
  const issueId = issue?.id ?? null
  const isEditingIssueContent = Boolean(issueId && editingIssueId === issueId)
  const draftIssueTitle =
    useWatch({ control: issueContentControl, name: 'title' }) ?? ''
  const draftIssueDescription =
    useWatch({ control: issueContentControl, name: 'description' }) ?? ''

  useEffect(() => {
    if (!isEditingIssueContent) {
      resetIssueContentForm({
        title: issue?.title ?? '',
        description: issue?.description ?? '',
      })
    }
  }, [
    isEditingIssueContent,
    issue?.description,
    issue?.id,
    issue?.title,
    resetIssueContentForm,
  ])

  async function handleSaveIssueContent(values: IssueContentFormValues) {
    try {
      await onUpdateIssue({
        title: values.title.trim(),
        description: values.description.trim() || null,
      })
      setEditingIssueId(null)
    } catch {
      // The shared mutation error is rendered below so the user keeps their edits.
    }
  }

  function startIssueContentEdit() {
    if (!issue) {
      return
    }

    onResetUpdateIssueError()
    resetIssueContentForm({
      title: issue.title,
      description: issue.description ?? '',
    })
    setEditingIssueId(issue.id)
  }

  function cancelIssueContentEdit() {
    resetIssueContentForm({
      title: issue?.title ?? '',
      description: issue?.description ?? '',
    })
    setEditingIssueId(null)
  }

  const canSaveIssueContent = Boolean(
    issue &&
      draftIssueTitle.trim() &&
      !isUpdatingIssue &&
      (draftIssueTitle.trim() !== issue.title ||
        (draftIssueDescription.trim() || null) !== (issue.description ?? null)),
  )

  if (!issue && isLoadingIssue) {
    return (
      <div className="content-page">
        <InlineState>Loading issue detail.</InlineState>
      </div>
    )
  }

  if (!issue) {
    return (
      <div className="content-page">
        {issueError ? (
          <ErrorState error={issueError} />
        ) : (
          <EmptyState title="Issue not found" body="Select an issue from the project list." />
        )}
      </div>
    )
  }

  return (
    <div className="content-page">
      <header className="content-header detail-content-header">
        <div>
          <BreadcrumbLine
            items={[
              currentWorkspace?.name ?? 'Workspace',
              selectedProject?.name ?? 'Project',
              issue.title,
            ]}
          />
          <Button type="button" variant="ghost" size="sm" onClick={onBackToProject}>
            <ArrowLeft aria-hidden="true" />
            Back to issues
          </Button>
        </div>
        <span className="app-pill">
          <PanelRight aria-hidden="true" />
          Properties
        </span>
      </header>

      <div className="issue-detail-layout">
        <article className="issue-detail-main">
          <div className="issue-title-block">
            {isEditingIssueContent ? (
              <form
                className="issue-edit-form"
                onSubmit={handleIssueContentSubmit(handleSaveIssueContent)}
                noValidate
              >
                <input
                  autoFocus
                  className="issue-title-input"
                  disabled={isUpdatingIssue}
                  aria-label="Issue title"
                  {...registerIssueContent('title')}
                />
                <textarea
                  className="issue-description-input"
                  disabled={isUpdatingIssue}
                  aria-label="Issue description"
                  placeholder="Add description..."
                  rows={5}
                  {...registerIssueContent('description')}
                />
                {issueContentErrors.title?.message ? (
                  <InlineNotice tone="warning">{issueContentErrors.title.message}</InlineNotice>
                ) : null}
                {issueContentErrors.description?.message ? (
                  <InlineNotice tone="warning">
                    {issueContentErrors.description.message}
                  </InlineNotice>
                ) : null}
                {updateIssueError ? <ErrorState error={updateIssueError} /> : null}
                <div className="issue-edit-actions">
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={cancelIssueContentEdit}
                    disabled={isUpdatingIssue}
                  >
                    Cancel
                  </Button>
                  <Button type="submit" disabled={!canSaveIssueContent}>
                    {isUpdatingIssue ? (
                      <Loader2 aria-hidden="true" className="auth-spin" />
                    ) : (
                      <Check aria-hidden="true" />
                    )}
                    Save
                  </Button>
                </div>
              </form>
            ) : (
              <div className="issue-title-display">
                <div className="issue-title-content">
                  <h1>{issue.title}</h1>
                  {issue.description ? (
                    <p>{issue.description}</p>
                  ) : (
                    <p className="muted-placeholder">No description yet.</p>
                  )}
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={startIssueContentEdit}
                >
                  <Pencil aria-hidden="true" />
                  Edit
                </Button>
              </div>
            )}
          </div>

          {isLoadingIssue ? <InlineState>Refreshing issue details.</InlineState> : null}
          {issueError ? <ErrorState error={issueError} /> : null}

          <section className="detail-section">
            <div className="detail-section-title">
              <MessageSquare aria-hidden="true" />
              <h2>Comments</h2>
            </div>
            {isLoadingComments ? <InlineState>Loading comments.</InlineState> : null}
            {commentsError ? <ErrorState error={commentsError} /> : null}
            {comments.length === 0 && !isLoadingComments && !commentsError ? (
              <InlineState>No comments yet.</InlineState>
            ) : null}
            {hasMoreComments ? (
              <Button
                type="button"
                variant="ghost"
                disabled={isLoadingMoreComments}
                onClick={onLoadMoreComments}
              >
                {isLoadingMoreComments ? (
                  <Loader2 className="auth-spin" aria-hidden="true" />
                ) : null}
                {isLoadingMoreComments ? 'Loading comments' : 'Load earlier comments'}
              </Button>
            ) : null}
            <div className="comment-list">
              {comments.map((comment) => (
                <article className="comment-item" key={comment.id}>
                  <div className="comment-meta">
                    <strong>{comment.author.displayName || comment.author.email}</strong>
                    <span>{formatDate(comment.createdAt)}</span>
                  </div>
                  <p>{comment.body}</p>
                </article>
              ))}
            </div>
            <CommentForm
              onSubmit={onSubmitComment}
              isSubmitting={isSubmittingComment}
              error={commentError}
            />
          </section>

          <section className="detail-section">
            <div className="detail-section-title">
              <Activity aria-hidden="true" />
              <h2>Activity</h2>
            </div>
            {isLoadingActivities ? <InlineState>Loading activity.</InlineState> : null}
            {activitiesError ? <ErrorState error={activitiesError} /> : null}
            {activities.length === 0 && !isLoadingActivities && !activitiesError ? (
              <InlineState>No activity recorded yet.</InlineState>
            ) : null}
            {hasMoreActivities ? (
              <Button
                type="button"
                variant="ghost"
                disabled={isLoadingMoreActivities}
                onClick={onLoadMoreActivities}
              >
                {isLoadingMoreActivities ? (
                  <Loader2 className="auth-spin" aria-hidden="true" />
                ) : null}
                {isLoadingMoreActivities ? 'Loading activity' : 'Load earlier activity'}
              </Button>
            ) : null}
            <div className="activity-list">
              {activities.map((activity) => (
                <article className="activity-item" key={activity.id}>
                  <span className="activity-dot" />
                  <div>
                    <p>{formatActivity(activity)}</p>
                    <small>{formatDate(activity.createdAt)}</small>
                  </div>
                </article>
              ))}
            </div>
          </section>
        </article>

        <IssuePropertiesPanel
          issue={issue}
          selectedProject={selectedProject}
          projectMembers={projectMembers}
          projectLabels={projectLabels}
          projectWorkflowStates={projectWorkflowStates}
          currentUser={currentUser}
          onUpdateIssue={onUpdateIssue}
          onArchiveIssue={onArchiveIssue}
          isUpdatingIssue={isUpdatingIssue}
          updateIssueError={updateIssueError}
        />
      </div>
    </div>
  )
}

function CommentForm({
  onSubmit,
  isSubmitting,
  error,
}: {
  onSubmit: (values: CommentFormValues) => Promise<void>
  isSubmitting: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<CommentFormValues>({
    resolver: zodResolver(commentFormSchema),
    defaultValues: {
      body: '',
    },
  })
  const body = useWatch({ control, name: 'body' }) ?? ''

  async function submitComment(values: CommentFormValues) {
    try {
      await onSubmit(values)
      reset({ body: '' })
    } catch {
      // The mutation error is rendered below so the draft comment stays in place.
    }
  }

  return (
    <form className="comment-form" onSubmit={handleSubmit(submitComment)} noValidate>
      <label className="app-field">
        Add comment
        <textarea
          placeholder="Leave a comment..."
          rows={4}
          disabled={isSubmitting}
          {...register('body')}
        />
      </label>
      {errors.body?.message ? <InlineNotice tone="warning">{errors.body.message}</InlineNotice> : null}
      {error ? <ErrorState error={error} /> : null}
      <Button type="submit" disabled={!body.trim() || isSubmitting}>
        {isSubmitting ? (
          <Loader2 aria-hidden="true" className="auth-spin" />
        ) : (
          <Send aria-hidden="true" />
        )}
        Comment
      </Button>
    </form>
  )
}

function IssuePropertiesPanel({
  issue,
  selectedProject,
  projectMembers,
  projectLabels,
  projectWorkflowStates,
  currentUser,
  onUpdateIssue,
  onArchiveIssue,
  isUpdatingIssue,
  updateIssueError,
}: {
  issue: IssueSummary | IssueDetail
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  projectWorkflowStates: ProjectWorkflowState[]
  currentUser: AuthUser | null
  onUpdateIssue: (request: UpdateIssueRequest) => Promise<void>
  onArchiveIssue: () => Promise<void>
  isUpdatingIssue: boolean
  updateIssueError: Error | null
}) {
  const actor = issue.creator ?? issue.reporter ?? null
  const isArchived = issue.status === 'ARCHIVED'

  return (
    <aside className="issue-properties">
      <section className="property-section">
        <div className="property-section-title">
          <PanelRight aria-hidden="true" />
          <h2>Properties</h2>
        </div>
        <label className="property-field">
          <span>Status</span>
          <select
            value={issue.workflowState.id}
            onChange={(event) => {
              void onUpdateIssue({ workflowStateId: event.target.value }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue || projectWorkflowStates.length === 0}
          >
            {projectWorkflowStates.map((workflowState) => (
              <option key={workflowState.id} value={workflowState.id}>
                {workflowState.name}
              </option>
            ))}
          </select>
        </label>
        <label className="property-field">
          <span>Priority</span>
          <select
            value={issue.priority ?? ''}
            onChange={(event) => {
              void onUpdateIssue({
                priority: event.target.value ? (event.target.value as IssuePriority) : null,
              }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          >
            <option value="">No priority</option>
            {ISSUE_PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {PRIORITY_LABELS[priority]}
              </option>
            ))}
          </select>
        </label>
        <label className="property-field">
          <span>Assignee</span>
          <select
            value={issue.assignee?.id ?? ''}
            onChange={(event) => {
              void onUpdateIssue({
                assigneeUserId: event.target.value || null,
              }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          >
            <option value="">Unassigned</option>
            {projectMembers.map((member) => (
              <option key={member.id} value={member.userId}>
                {member.displayName || member.email}
              </option>
            ))}
          </select>
        </label>
        <label className="property-field">
          <span>Due date</span>
          <input
            type="date"
            value={issue.dueDate ?? ''}
            onChange={(event) => {
              void onUpdateIssue({
                dueDate: event.target.value || null,
              }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          />
        </label>
        <div className="property-field">
          <span>Labels</span>
          {projectLabels.length === 0 ? (
            <InlineNotice>No labels in this project.</InlineNotice>
          ) : (
            <div className="label-toggle-list">
              {projectLabels.map((label) => {
                const isSelected = issue.labels.some((issueLabel) => issueLabel.id === label.id)
                return (
                  <label className="label-toggle" key={label.id}>
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => {
                        const labelIds = isSelected
                          ? issue.labels
                              .filter((issueLabel) => issueLabel.id !== label.id)
                              .map((issueLabel) => issueLabel.id)
                          : [...issue.labels.map((issueLabel) => issueLabel.id), label.id]
                        void onUpdateIssue({ labelIds }).catch(() => undefined)
                      }}
                      disabled={isUpdatingIssue}
                    />
                    <LabelBadge label={label} />
                  </label>
                )
              })}
            </div>
          )}
        </div>
        {updateIssueError ? (
          <InlineNotice tone="warning">
            {getErrorMessage(updateIssueError)}
          </InlineNotice>
        ) : null}
        {isArchived ? (
          <InlineNotice>This issue is archived.</InlineNotice>
        ) : (
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              void onArchiveIssue().catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          >
            {isUpdatingIssue ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Archive aria-hidden="true" />
            )}
            Archive issue
          </Button>
        )}
      </section>

      <section className="property-section">
        <div className="property-row">
          <span>
            <FolderKanban aria-hidden="true" />
            Project
          </span>
          <strong>{selectedProject?.name ?? 'Unknown'}</strong>
        </div>
        <div className="property-row">
          <span>
            <UserCircle aria-hidden="true" />
            Creator
          </span>
          <strong>
            {actor
              ? actor.displayName || actor.email
              : currentUser
                ? currentUser.displayName || currentUser.email
                : 'Unknown'}
          </strong>
        </div>
        <div className="property-row">
          <span>
            <Clock3 aria-hidden="true" />
            Created
          </span>
          <strong>{formatDate(issue.createdAt)}</strong>
        </div>
        <div className="property-row">
          <span>
            <Clock3 aria-hidden="true" />
            Updated
          </span>
          <strong>{formatDate(issue.updatedAt)}</strong>
        </div>
      </section>
    </aside>
  )
}

