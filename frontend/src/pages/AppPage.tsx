import { useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Clock3,
  FolderKanban,
  ListChecks,
  Loader2,
  LogOut,
  MessageSquare,
  Plus,
  Send,
} from 'lucide-react'
import { ApiError } from '@/api/client'
import { getCurrentSession } from '@/auth/auth-api'
import { Button } from '@/components/ui/button'
import {
  createIssue,
  createIssueComment,
  createProject,
  getIssue,
  listIssueActivities,
  listIssues,
  listProjects,
  type ActivityEvent,
  type IssueDetail,
  type IssueSummary,
  type Project,
} from '@/work/work-api'

type AppPageProps = {
  onSignOut: () => void
}

export function AppPage({ onSignOut }: AppPageProps) {
  const queryClient = useQueryClient()
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null)
  const [selectedIssueId, setSelectedIssueId] = useState<string | null>(null)
  const [projectName, setProjectName] = useState('')
  const [projectDescription, setProjectDescription] = useState('')
  const [issueTitle, setIssueTitle] = useState('')
  const [issueDescription, setIssueDescription] = useState('')
  const [commentBody, setCommentBody] = useState('')

  const currentSessionQuery = useQuery({
    queryKey: ['current-session'],
    queryFn: getCurrentSession,
    retry: false,
  })

  const projectsQuery = useQuery({
    queryKey: ['projects'],
    queryFn: listProjects,
    retry: false,
  })

  const projects = projectsQuery.data ?? []
  const effectiveSelectedProjectId =
    selectedProjectId && projects.some((project) => project.id === selectedProjectId)
      ? selectedProjectId
      : projects[0]?.id ?? null
  const selectedProject =
    projects.find((project) => project.id === effectiveSelectedProjectId) ?? null

  const issuesQuery = useQuery({
    queryKey: ['issues', effectiveSelectedProjectId],
    queryFn: () => listIssues(effectiveSelectedProjectId ?? ''),
    enabled: Boolean(effectiveSelectedProjectId),
    retry: false,
  })

  const issues = issuesQuery.data ?? []
  const effectiveSelectedIssueId =
    selectedIssueId && issues.some((issue) => issue.id === selectedIssueId)
      ? selectedIssueId
      : null
  const selectedIssueSummary = issues.find((issue) => issue.id === effectiveSelectedIssueId) ?? null

  const issueDetailQuery = useQuery({
    queryKey: ['issue', effectiveSelectedIssueId],
    queryFn: () => getIssue(effectiveSelectedIssueId ?? ''),
    enabled: Boolean(effectiveSelectedIssueId),
    retry: false,
  })

  const activitiesQuery = useQuery({
    queryKey: ['issue-activities', effectiveSelectedIssueId],
    queryFn: () => listIssueActivities(effectiveSelectedIssueId ?? ''),
    enabled: Boolean(effectiveSelectedIssueId),
    retry: false,
  })

  const createProjectMutation = useMutation({
    mutationFn: createProject,
    onSuccess: async (project) => {
      setProjectName('')
      setProjectDescription('')
      setSelectedProjectId(project.id)
      setSelectedIssueId(null)
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
    },
  })

  const createIssueMutation = useMutation({
    mutationFn: createIssue,
    onSuccess: async (issue) => {
      setIssueTitle('')
      setIssueDescription('')
      setSelectedIssueId(issue.id)
      await queryClient.invalidateQueries({ queryKey: ['issues', issue.projectId] })
    },
  })

  const createCommentMutation = useMutation({
    mutationFn: ({ issueId, body }: { issueId: string; projectId: string; body: string }) =>
      createIssueComment(issueId, { body }),
    onSuccess: async (_, variables) => {
      setCommentBody('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issue', variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-activities', variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issues', variables.projectId] }),
      ])
    },
  })

  function handleCreateProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const name = projectName.trim()
    if (!name) {
      return
    }

    createProjectMutation.mutate({
      name,
      description: projectDescription.trim() || undefined,
    })
  }

  function handleCreateIssue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const title = issueTitle.trim()
    if (!effectiveSelectedProjectId || !title) {
      return
    }

    createIssueMutation.mutate({
      projectId: effectiveSelectedProjectId,
      title,
      description: issueDescription.trim() || undefined,
    })
  }

  function handleCreateComment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const body = commentBody.trim()
    if (!effectiveSelectedIssueId || !effectiveSelectedProjectId || !body) {
      return
    }

    createCommentMutation.mutate({
      issueId: effectiveSelectedIssueId,
      projectId: effectiveSelectedProjectId,
      body,
    })
  }

  const currentSession = currentSessionQuery.data
  const currentUser = currentSession?.user
  const currentWorkspace = currentSession?.workspace
  const issueDetail = issueDetailQuery.data
  const activities = activitiesQuery.data ?? []
  const activeIssue = issueDetail ?? selectedIssueSummary

  return (
    <main className="app-shell">
      <aside className="app-sidebar">
        <div className="app-sidebar-top">
          <div>
            <p className="app-logo">FlowAI</p>
            <p className="app-muted">{currentWorkspace?.name ?? 'Workspace'}</p>
          </div>
          <Button type="button" variant="outline" onClick={onSignOut}>
            <LogOut aria-hidden="true" />
            Sign out
          </Button>
        </div>

        <section className="app-panel">
          <PanelHeader
            icon={<FolderKanban aria-hidden="true" />}
            label="Projects"
            title="Workspace projects"
          />
          {currentSessionQuery.isLoading ? (
            <InlineState>Loading your workspace.</InlineState>
          ) : null}
          {currentSessionQuery.isError ? (
            <ErrorState error={currentSessionQuery.error} />
          ) : null}
          {projectsQuery.isLoading ? <InlineState>Loading projects.</InlineState> : null}
          {projectsQuery.isError ? <ErrorState error={projectsQuery.error} /> : null}
          <ProjectList
            projects={projects}
            selectedProjectId={effectiveSelectedProjectId}
            onSelectProject={(projectId) => {
              setSelectedProjectId(projectId)
              setSelectedIssueId(null)
            }}
          />
        </section>

        <form className="app-form app-panel" onSubmit={handleCreateProject}>
          <PanelHeader icon={<Plus aria-hidden="true" />} label="New" title="Create project" />
          <label className="app-field">
            Name
            <input
              name="projectName"
              placeholder="Backend MVP"
              value={projectName}
              onChange={(event) => setProjectName(event.target.value)}
            />
          </label>
          <label className="app-field">
            Description
            <textarea
              name="projectDescription"
              placeholder="Core API and product foundation"
              rows={3}
              value={projectDescription}
              onChange={(event) => setProjectDescription(event.target.value)}
            />
          </label>
          {createProjectMutation.isError ? (
            <ErrorState error={createProjectMutation.error} />
          ) : null}
          <Button
            type="submit"
            disabled={!projectName.trim() || createProjectMutation.isPending}
          >
            {createProjectMutation.isPending ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            Create project
          </Button>
        </form>
      </aside>

      <section className="app-main">
        <header className="app-toolbar">
          <div>
            <p className="app-muted">Signed in as {currentUser?.email ?? 'Loading'}</p>
            <h1 className="app-title">{selectedProject?.name ?? 'Projects'}</h1>
          </div>
          {selectedProject ? (
            <span className="app-pill">{issues.length} issues</span>
          ) : null}
        </header>

        <div className="app-workspace">
          <section className="app-column app-issues-column">
            <div className="app-section-heading">
              <PanelHeader
                icon={<ListChecks aria-hidden="true" />}
                label="Issues"
                title={selectedProject ? selectedProject.name : 'Select a project'}
              />
              {selectedProject?.description ? (
                <p className="app-muted">{selectedProject.description}</p>
              ) : null}
            </div>

            <form className="app-form app-create-issue" onSubmit={handleCreateIssue}>
              <label className="app-field">
                Title
                <input
                  name="issueTitle"
                  placeholder="Add authentication flow tests"
                  value={issueTitle}
                  onChange={(event) => setIssueTitle(event.target.value)}
                  disabled={!selectedProject}
                />
              </label>
              <label className="app-field">
                Description
                <textarea
                  name="issueDescription"
                  placeholder="Describe the work and expected outcome"
                  rows={3}
                  value={issueDescription}
                  onChange={(event) => setIssueDescription(event.target.value)}
                  disabled={!selectedProject}
                />
              </label>
              {createIssueMutation.isError ? <ErrorState error={createIssueMutation.error} /> : null}
              <Button
                type="submit"
                disabled={!selectedProject || !issueTitle.trim() || createIssueMutation.isPending}
              >
                {createIssueMutation.isPending ? (
                  <Loader2 aria-hidden="true" className="auth-spin" />
                ) : (
                  <Plus aria-hidden="true" />
                )}
                Create issue
              </Button>
            </form>

            {issuesQuery.isLoading ? <InlineState>Loading issues.</InlineState> : null}
            {issuesQuery.isError ? <ErrorState error={issuesQuery.error} /> : null}
            {!selectedProject && !projectsQuery.isLoading ? (
              <EmptyState title="No project selected" body="Create or select a project to start tracking issues." />
            ) : null}
            {selectedProject && !issuesQuery.isLoading && !issuesQuery.isError && issues.length === 0 ? (
              <EmptyState title="No issues yet" body="Create the first issue for this project." />
            ) : null}
            <IssueList
              issues={issues}
              selectedIssueId={effectiveSelectedIssueId}
              onSelectIssue={setSelectedIssueId}
            />
          </section>

          <IssueDetailPanel
            issue={activeIssue}
            issueDetail={issueDetail}
            activities={activities}
            isLoadingIssue={issueDetailQuery.isLoading}
            issueError={issueDetailQuery.error}
            isLoadingActivities={activitiesQuery.isLoading}
            activitiesError={activitiesQuery.error}
            commentBody={commentBody}
            onCommentBodyChange={setCommentBody}
            onSubmitComment={handleCreateComment}
            isSubmittingComment={createCommentMutation.isPending}
            commentError={createCommentMutation.error}
          />
        </div>
      </section>
    </main>
  )
}

function PanelHeader({
  icon,
  label,
  title,
}: {
  icon: ReactNode
  label: string
  title: string
}) {
  return (
    <div className="app-panel-header">
      <span className="app-panel-icon">{icon}</span>
      <div>
        <p className="app-muted">{label}</p>
        <h2>{title}</h2>
      </div>
    </div>
  )
}

function ProjectList({
  projects,
  selectedProjectId,
  onSelectProject,
}: {
  projects: Project[]
  selectedProjectId: string | null
  onSelectProject: (projectId: string) => void
}) {
  if (projects.length === 0) {
    return <EmptyState title="No projects yet" body="Create your first project in this workspace." />
  }

  return (
    <div className="app-list">
      {projects.map((project) => (
        <button
          className="app-list-item"
          data-active={project.id === selectedProjectId}
          key={project.id}
          type="button"
          onClick={() => onSelectProject(project.id)}
        >
          <span>{project.name}</span>
          {project.description ? <small>{project.description}</small> : null}
        </button>
      ))}
    </div>
  )
}

function IssueList({
  issues,
  selectedIssueId,
  onSelectIssue,
}: {
  issues: IssueSummary[]
  selectedIssueId: string | null
  onSelectIssue: (issueId: string) => void
}) {
  return (
    <div className="issue-list">
      {issues.map((issue) => (
        <button
          className="issue-row"
          data-active={issue.id === selectedIssueId}
          key={issue.id}
          type="button"
          onClick={() => onSelectIssue(issue.id)}
        >
          <span className="issue-row-main">
            <strong>{issue.title}</strong>
            {issue.description ? <small>{issue.description}</small> : null}
          </span>
          <span className="issue-row-meta">
            <StatusBadge status={issue.status} />
            {issue.commentCount ? (
              <span className="issue-comment-count">
                <MessageSquare aria-hidden="true" />
                {issue.commentCount}
              </span>
            ) : null}
          </span>
        </button>
      ))}
    </div>
  )
}

function IssueDetailPanel({
  issue,
  issueDetail,
  activities,
  isLoadingIssue,
  issueError,
  isLoadingActivities,
  activitiesError,
  commentBody,
  onCommentBodyChange,
  onSubmitComment,
  isSubmittingComment,
  commentError,
}: {
  issue: IssueSummary | IssueDetail | null
  issueDetail: IssueDetail | undefined
  activities: ActivityEvent[]
  isLoadingIssue: boolean
  issueError: Error | null
  isLoadingActivities: boolean
  activitiesError: Error | null
  commentBody: string
  onCommentBodyChange: (body: string) => void
  onSubmitComment: (event: FormEvent<HTMLFormElement>) => void
  isSubmittingComment: boolean
  commentError: Error | null
}) {
  const comments = issueDetail?.comments ?? []

  if (!issue) {
    return (
      <aside className="app-column issue-detail">
        <EmptyState title="No issue selected" body="Choose an issue to view comments and activity." />
      </aside>
    )
  }

  return (
    <aside className="app-column issue-detail">
      <div className="issue-detail-header">
        <div>
          <p className="app-muted">Issue detail</p>
          <h2>{issue.title}</h2>
        </div>
        <StatusBadge status={issue.status} />
      </div>
      {issue.description ? <p className="issue-description">{issue.description}</p> : null}
      <dl className="issue-facts">
        <div>
          <dt>Priority</dt>
          <dd>{issue.priority ?? 'None'}</dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>{formatDate(issue.updatedAt)}</dd>
        </div>
      </dl>

      {isLoadingIssue ? <InlineState>Loading issue details.</InlineState> : null}
      {issueError ? <ErrorState error={issueError} /> : null}

      <section className="detail-section">
        <div className="detail-section-title">
          <MessageSquare aria-hidden="true" />
          <h3>Comments</h3>
        </div>
        {comments.length === 0 && !isLoadingIssue ? (
          <InlineState>No comments yet.</InlineState>
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
        <form className="app-form comment-form" onSubmit={onSubmitComment}>
          <label className="app-field">
            Add comment
            <textarea
              name="commentBody"
              placeholder="Leave a note for this issue"
              rows={3}
              value={commentBody}
              onChange={(event) => onCommentBodyChange(event.target.value)}
            />
          </label>
          {commentError ? <ErrorState error={commentError} /> : null}
          <Button type="submit" disabled={!commentBody.trim() || isSubmittingComment}>
            {isSubmittingComment ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Send aria-hidden="true" />
            )}
            Add comment
          </Button>
        </form>
      </section>

      <section className="detail-section">
        <div className="detail-section-title">
          <Clock3 aria-hidden="true" />
          <h3>Activity</h3>
        </div>
        {isLoadingActivities ? <InlineState>Loading activity.</InlineState> : null}
        {activitiesError ? <ErrorState error={activitiesError} /> : null}
        {activities.length === 0 && !isLoadingActivities && !activitiesError ? (
          <InlineState>No activity recorded yet.</InlineState>
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
    </aside>
  )
}

function StatusBadge({ status }: { status: string }) {
  return <span className="status-badge">{formatStatus(status)}</span>
}

function InlineState({ children }: { children: ReactNode }) {
  return <p className="app-state">{children}</p>
}

function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty-state">
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  )
}

function ErrorState({ error }: { error: Error }) {
  return <p className="app-error">{getErrorMessage(error)}</p>
}

function getErrorMessage(error: Error) {
  if (error instanceof ApiError) {
    return error.message
  }

  return 'Unable to load this workspace data.'
}

function formatStatus(status: string) {
  return status
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatActivity(activity: ActivityEvent) {
  const actor = activity.actor.displayName || activity.actor.email
  const metadata = activity.metadata ?? {}

  switch (activity.eventType) {
    case 'PROJECT_CREATED':
      return `${actor} created project ${readMetadata(metadata, 'projectName') ?? 'this project'}`
    case 'ISSUE_CREATED':
      return `${actor} created this issue`
    case 'COMMENT_CREATED':
      return `${actor} commented`
    case 'ISSUE_STATUS_CHANGED': {
      const fromStatus = readMetadata(metadata, 'fromStatus')
      const toStatus = readMetadata(metadata, 'toStatus')
      if (fromStatus && toStatus) {
        return `${actor} changed status from ${formatStatus(fromStatus)} to ${formatStatus(toStatus)}`
      }

      return `${actor} changed issue status`
    }
    default:
      return `${actor} recorded ${formatStatus(activity.eventType)}`
  }
}

function readMetadata(metadata: Record<string, unknown>, key: string) {
  const value = metadata[key]
  return typeof value === 'string' ? value : undefined
}
