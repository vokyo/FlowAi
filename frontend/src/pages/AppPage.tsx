import { useEffect, useMemo, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity,
  ArrowLeft,
  Building2,
  ChevronDown,
  ChevronRight,
  CheckCircle2,
  Circle,
  CircleDot,
  Clock3,
  Check,
  Flag,
  FolderKanban,
  LayoutList,
  Loader2,
  LogOut,
  MessageSquare,
  MoreHorizontal,
  PanelRight,
  Pencil,
  Plus,
  Send,
  UserCircle,
  X,
} from 'lucide-react'
import { useNavigate, useParams } from 'react-router'
import { ApiError } from '@/api/client'
import { getCurrentSession, type AuthUser, type AuthWorkspace } from '@/auth/auth-api'
import { Button } from '@/components/ui/button'
import {
  createIssue,
  createIssueComment,
  createProject,
  getIssue,
  listIssueActivities,
  listIssues,
  listProjects,
  updateIssue,
  type ActivityEvent,
  type IssueDetail,
  type IssuePriority,
  type IssueStatus,
  type IssueSummary,
  type Project,
  type UpdateIssueRequest,
} from '@/work/work-api'

const ISSUE_STATUSES = ['TODO', 'IN_PROGRESS', 'DONE', 'ARCHIVED'] as const
const ISSUE_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const
const EMPTY_PROJECTS: Project[] = []
const EMPTY_ISSUES: IssueSummary[] = []

const STATUS_LABELS: Record<(typeof ISSUE_STATUSES)[number], string> = {
  TODO: 'Todo',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
  ARCHIVED: 'Archived',
}

const PRIORITY_LABELS: Record<(typeof ISSUE_PRIORITIES)[number], string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  URGENT: 'Urgent',
}

type AppPageProps = {
  onSignOut: () => void
}

type AppRouteParams = {
  workspaceId?: string
  projectId?: string
  issueId?: string
}

type IssueGroup = {
  status: IssueStatus
  label: string
  issues: IssueSummary[]
}

type CreateDialog = 'project' | 'issue' | null

export function AppPage({ onSignOut }: AppPageProps) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const {
    workspaceId: routeWorkspaceId,
    projectId: routeProjectId,
    issueId: routeIssueId,
  } = useParams<AppRouteParams>()
  const [activeCreateDialog, setActiveCreateDialog] = useState<CreateDialog>(null)
  const [areProjectsOpen, setAreProjectsOpen] = useState(true)
  const [projectName, setProjectName] = useState('')
  const [projectDescription, setProjectDescription] = useState('')
  const [issueTitle, setIssueTitle] = useState('')
  const [issueDescription, setIssueDescription] = useState('')
  const [issueStatus, setIssueStatus] = useState<IssueStatus>('TODO')
  const [issuePriority, setIssuePriority] = useState<IssuePriority | ''>('')
  const [commentBody, setCommentBody] = useState('')

  const currentSessionQuery = useQuery({
    queryKey: ['current-session'],
    queryFn: getCurrentSession,
    retry: false,
  })

  const sessionWorkspace = currentSessionQuery.data?.workspace ?? null
  const currentUser = currentSessionQuery.data?.user ?? null
  const currentWorkspace = sessionWorkspace
  const currentWorkspaceId = currentWorkspace?.id ?? null
  const routeMatchesCurrentWorkspace =
    !routeWorkspaceId || !currentWorkspaceId || routeWorkspaceId === currentWorkspaceId
  const canLoadCurrentWorkspace = Boolean(currentWorkspaceId && routeMatchesCurrentWorkspace)

  const projectsQuery = useQuery({
    queryKey: ['projects', currentWorkspaceId],
    queryFn: listProjects,
    enabled: canLoadCurrentWorkspace,
    retry: false,
  })

  const projects = projectsQuery.data ?? EMPTY_PROJECTS
  const selectedProject = routeProjectId
    ? projects.find((project) => project.id === routeProjectId) ?? null
    : projects[0] ?? null
  const selectedProjectId = selectedProject?.id ?? null

  const issuesQuery = useQuery({
    queryKey: ['issues', currentWorkspaceId, selectedProjectId],
    queryFn: () => listIssues(selectedProjectId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    retry: false,
  })

  const issues = issuesQuery.data ?? EMPTY_ISSUES
  const issueGroups = useMemo(() => groupIssuesByStatus(issues), [issues])
  const selectedIssueSummary = routeIssueId
    ? issues.find((issue) => issue.id === routeIssueId) ?? null
    : null

  const issueDetailQuery = useQuery({
    queryKey: ['issue', routeIssueId],
    queryFn: () => getIssue(routeIssueId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProject && routeIssueId),
    retry: false,
  })

  const activitiesQuery = useQuery({
    queryKey: ['issue-activities', routeIssueId],
    queryFn: () => listIssueActivities(routeIssueId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProject && routeIssueId),
    retry: false,
  })

  useEffect(() => {
    if (!currentWorkspaceId || !canLoadCurrentWorkspace || !projectsQuery.isSuccess) {
      return
    }

    if (projects.length === 0) {
      if (routeProjectId || routeIssueId) {
        navigate('/app', { replace: true })
      }
      return
    }

    const hasRouteProject = Boolean(
      routeProjectId && projects.some((project) => project.id === routeProjectId),
    )

    if (!routeProjectId || !hasRouteProject) {
      navigate(projectPath(currentWorkspaceId, projects[0].id), { replace: true })
    }
  }, [
    canLoadCurrentWorkspace,
    currentWorkspaceId,
    navigate,
    projects,
    projectsQuery.isSuccess,
    routeIssueId,
    routeProjectId,
  ])

  const createProjectMutation = useMutation({
    mutationFn: createProject,
    onSuccess: async (project) => {
      setProjectName('')
      setProjectDescription('')
      setActiveCreateDialog(null)
      await queryClient.invalidateQueries({ queryKey: ['projects', currentWorkspaceId] })
      if (currentWorkspaceId) {
        navigate(projectPath(currentWorkspaceId, project.id))
      }
    },
  })

  const createIssueMutation = useMutation({
    mutationFn: createIssue,
    onSuccess: async (issue) => {
      setIssueTitle('')
      setIssueDescription('')
      setIssueStatus('TODO')
      setIssuePriority('')
      setActiveCreateDialog(null)
      await queryClient.invalidateQueries({
        queryKey: ['issues', currentWorkspaceId, issue.projectId],
      })
      if (currentWorkspaceId) {
        navigate(issuePath(currentWorkspaceId, issue.projectId, issue.id))
      }
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
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, variables.projectId],
        }),
      ])
    },
  })

  const updateIssueMutation = useMutation({
    mutationFn: ({
      issueId,
      request,
    }: {
      issueId: string
      projectId: string
      request: UpdateIssueRequest
    }) => updateIssue(issueId, request),
    onSuccess: async (issue, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issue', variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-activities', variables.issueId] }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, issue.projectId],
        }),
      ])
    },
  })

  function openCreateProjectDialog() {
    createProjectMutation.reset()
    setProjectName('')
    setProjectDescription('')
    setActiveCreateDialog('project')
  }

  function openCreateIssueDialog(status: IssueStatus = 'TODO') {
    createIssueMutation.reset()
    setIssueTitle('')
    setIssueDescription('')
    setIssueStatus(status)
    setIssuePriority('')
    setActiveCreateDialog('issue')
  }

  function closeCreateDialog() {
    if (createProjectMutation.isPending || createIssueMutation.isPending) {
      return
    }

    setActiveCreateDialog(null)
  }

  function handleProjectSelect(projectId: string) {
    if (!currentWorkspaceId) {
      return
    }

    navigate(projectPath(currentWorkspaceId, projectId))
  }

  function handleIssueSelect(issueId: string) {
    if (!currentWorkspaceId || !selectedProjectId) {
      return
    }

    navigate(issuePath(currentWorkspaceId, selectedProjectId, issueId))
  }

  function handleCreateProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const name = projectName.trim()
    if (!name || !canLoadCurrentWorkspace) {
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
    if (!selectedProjectId || !title) {
      return
    }

    createIssueMutation.mutate({
      projectId: selectedProjectId,
      title,
      description: issueDescription.trim() || undefined,
      status: issueStatus,
      priority: issuePriority || undefined,
    })
  }

  function handleCreateComment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const body = commentBody.trim()
    if (!routeIssueId || !selectedProjectId || !body) {
      return
    }

    createCommentMutation.mutate({
      issueId: routeIssueId,
      projectId: selectedProjectId,
      body,
    })
  }

  async function handleUpdateIssue(request: UpdateIssueRequest) {
    if (!routeIssueId || !selectedProjectId) {
      return
    }

    updateIssueMutation.reset()
    await updateIssueMutation.mutateAsync({
      issueId: routeIssueId,
      projectId: selectedProjectId,
      request,
    })
  }

  const activeIssue = issueDetailQuery.data ?? selectedIssueSummary
  const activities = activitiesQuery.data ?? []
  const isIssueDetailRoute = Boolean(routeIssueId)

  return (
    <main className="app-shell">
      <WorkspaceSidebar
        currentWorkspace={currentWorkspace}
        isLoadingWorkspace={currentSessionQuery.isLoading}
        projects={projects}
        selectedProjectId={selectedProjectId}
        isLoadingProjects={projectsQuery.isLoading}
        projectsError={projectsQuery.error}
        areProjectsOpen={areProjectsOpen}
        onToggleProjects={() => setAreProjectsOpen((isOpen) => !isOpen)}
        onOpenCreateProject={openCreateProjectDialog}
        canCreateProject={canLoadCurrentWorkspace}
        onProjectSelect={handleProjectSelect}
        onSignOut={onSignOut}
      />

      <section className="app-main">
        {!routeMatchesCurrentWorkspace ? (
          <WorkspaceMismatchState
            currentWorkspace={currentWorkspace}
            routeWorkspaceId={routeWorkspaceId}
          />
        ) : isIssueDetailRoute ? (
          <IssueDetailView
            issue={activeIssue}
            issueDetail={issueDetailQuery.data}
            selectedProject={selectedProject}
            currentWorkspace={currentWorkspace}
            currentUser={currentUser}
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
            onBackToProject={() => {
              if (currentWorkspaceId && selectedProjectId) {
                navigate(projectPath(currentWorkspaceId, selectedProjectId))
              }
            }}
            onUpdateIssue={handleUpdateIssue}
            isUpdatingIssue={updateIssueMutation.isPending}
            updateIssueError={updateIssueMutation.error}
            onResetUpdateIssueError={() => updateIssueMutation.reset()}
          />
        ) : (
          <ProjectIssuesView
            currentWorkspace={currentWorkspace}
            selectedProject={selectedProject}
            issues={issues}
            issueGroups={issueGroups}
            selectedIssueId={routeIssueId ?? null}
            isLoadingIssues={issuesQuery.isLoading}
            issuesError={issuesQuery.error}
            isLoadingProjects={projectsQuery.isLoading}
            onOpenCreateIssue={openCreateIssueDialog}
            onIssueSelect={handleIssueSelect}
          />
        )}
      </section>

      <CreateProjectDialog
        isOpen={activeCreateDialog === 'project'}
        projectName={projectName}
        projectDescription={projectDescription}
        onProjectNameChange={setProjectName}
        onProjectDescriptionChange={setProjectDescription}
        onSubmit={handleCreateProject}
        onClose={closeCreateDialog}
        canCreateProject={canLoadCurrentWorkspace}
        isSubmitting={createProjectMutation.isPending}
        error={createProjectMutation.error}
      />
      <CreateIssueDialog
        isOpen={activeCreateDialog === 'issue'}
        selectedProject={selectedProject}
        issueTitle={issueTitle}
        issueDescription={issueDescription}
        issueStatus={issueStatus}
        issuePriority={issuePriority}
        onIssueTitleChange={setIssueTitle}
        onIssueDescriptionChange={setIssueDescription}
        onIssueStatusChange={setIssueStatus}
        onIssuePriorityChange={setIssuePriority}
        onSubmit={handleCreateIssue}
        onClose={closeCreateDialog}
        isSubmitting={createIssueMutation.isPending}
        error={createIssueMutation.error}
      />
    </main>
  )
}

function WorkspaceSidebar({
  currentWorkspace,
  isLoadingWorkspace,
  projects,
  selectedProjectId,
  isLoadingProjects,
  projectsError,
  areProjectsOpen,
  onToggleProjects,
  onOpenCreateProject,
  canCreateProject,
  onProjectSelect,
  onSignOut,
}: {
  currentWorkspace: AuthWorkspace | null
  isLoadingWorkspace: boolean
  projects: Project[]
  selectedProjectId: string | null
  isLoadingProjects: boolean
  projectsError: Error | null
  areProjectsOpen: boolean
  onToggleProjects: () => void
  onOpenCreateProject: () => void
  canCreateProject: boolean
  onProjectSelect: (projectId: string) => void
  onSignOut: () => void
}) {
  return (
    <aside className="app-sidebar">
      <div className="sidebar-topbar">
        <div className="workspace-switcher">
          <span className="workspace-avatar" aria-hidden="true">
            {getInitials(currentWorkspace?.name ?? 'FlowAI')}
          </span>
          <div className="workspace-select-label">
            Workspace
            <strong>{isLoadingWorkspace ? 'Loading workspace' : currentWorkspace?.name ?? 'Workspace'}</strong>
          </div>
        </div>
        <Button type="button" variant="ghost" size="icon" onClick={onSignOut} aria-label="Sign out">
          <LogOut aria-hidden="true" />
        </Button>
      </div>

      <nav className="sidebar-section" aria-label="Projects">
        <div className="sidebar-section-header sidebar-section-header-interactive">
          <button
            className="sidebar-collapse-button"
            type="button"
            onClick={onToggleProjects}
            aria-expanded={areProjectsOpen}
          >
            {areProjectsOpen ? (
              <ChevronDown aria-hidden="true" />
            ) : (
              <ChevronRight aria-hidden="true" />
            )}
            <FolderKanban aria-hidden="true" />
            Projects
          </button>
          <span className="sidebar-section-actions">
            <small>{projects.length}</small>
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={onOpenCreateProject}
              disabled={!canCreateProject}
              aria-label="Create project"
              title="Create project"
            >
              <Plus aria-hidden="true" />
            </Button>
          </span>
        </div>
        {isLoadingProjects ? <InlineState>Loading projects.</InlineState> : null}
        {projectsError ? <ErrorState error={projectsError} /> : null}
        {areProjectsOpen ? (
          <ProjectList
            projects={projects}
            selectedProjectId={selectedProjectId}
            onProjectSelect={onProjectSelect}
            isLoading={isLoadingProjects}
          />
        ) : null}
      </nav>
    </aside>
  )
}

function ProjectList({
  projects,
  selectedProjectId,
  onProjectSelect,
  isLoading,
}: {
  projects: Project[]
  selectedProjectId: string | null
  onProjectSelect: (projectId: string) => void
  isLoading: boolean
}) {
  if (!isLoading && projects.length === 0) {
    return <InlineState>No projects yet.</InlineState>
  }

  return (
    <div className="sidebar-list">
      {projects.map((project) => (
        <button
          className="sidebar-list-item"
          data-active={project.id === selectedProjectId}
          key={project.id}
          type="button"
          onClick={() => onProjectSelect(project.id)}
        >
          <FolderKanban aria-hidden="true" />
          <span>
            <strong>{project.name}</strong>
            {project.description ? <small>{project.description}</small> : null}
          </span>
        </button>
      ))}
    </div>
  )
}

function ProjectIssuesView({
  currentWorkspace,
  selectedProject,
  issues,
  issueGroups,
  selectedIssueId,
  isLoadingIssues,
  issuesError,
  isLoadingProjects,
  onOpenCreateIssue,
  onIssueSelect,
}: {
  currentWorkspace: AuthWorkspace | null
  selectedProject: Project | null
  issues: IssueSummary[]
  issueGroups: IssueGroup[]
  selectedIssueId: string | null
  isLoadingIssues: boolean
  issuesError: Error | null
  isLoadingProjects: boolean
  onOpenCreateIssue: (status?: IssueStatus) => void
  onIssueSelect: (issueId: string) => void
}) {
  return (
    <div className="content-page">
      <header className="content-header">
        <div>
          <BreadcrumbLine
            items={[currentWorkspace?.name ?? 'Workspace', selectedProject?.name ?? 'Issues']}
          />
          <h1>{selectedProject?.name ?? 'Select a project'}</h1>
          {selectedProject?.description ? <p>{selectedProject.description}</p> : null}
        </div>
        <div className="content-header-actions">
          {selectedProject ? (
            <span className="app-pill">
              <LayoutList aria-hidden="true" />
              {issues.length} issues
            </span>
          ) : null}
          <Button
            type="button"
            onClick={() => onOpenCreateIssue()}
            disabled={!selectedProject}
            aria-label="Create issue"
          >
            <Plus aria-hidden="true" />
            New issue
          </Button>
        </div>
      </header>

      {isLoadingProjects ? <InlineState>Loading workspace projects.</InlineState> : null}
      {!selectedProject && !isLoadingProjects ? (
        <EmptyState
          title="No project selected"
          body="Create or select a project from the sidebar to start tracking issues."
        />
      ) : null}
      {isLoadingIssues ? <InlineState>Loading issues.</InlineState> : null}
      {issuesError ? <ErrorState error={issuesError} /> : null}

      {selectedProject && !issuesError ? (
        <div className="status-list" aria-label="Issues grouped by status">
          {issueGroups.map((group) => (
            <IssueStatusSection
              group={group}
              key={group.status}
              selectedIssueId={selectedIssueId}
              onIssueSelect={onIssueSelect}
              onOpenCreateIssue={onOpenCreateIssue}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

function IssueStatusSection({
  group,
  selectedIssueId,
  onIssueSelect,
  onOpenCreateIssue,
}: {
  group: IssueGroup
  selectedIssueId: string | null
  onIssueSelect: (issueId: string) => void
  onOpenCreateIssue: (status?: IssueStatus) => void
}) {
  return (
    <section className="status-section">
      <div className="status-section-header">
        <span>
          <StatusIcon status={group.status} />
          {group.label}
        </span>
        <span className="status-section-actions">
          <small>{group.issues.length}</small>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={() => onOpenCreateIssue(group.status)}
            aria-label={`Create issue in ${group.label}`}
            title="Create issue"
          >
            <Plus aria-hidden="true" />
          </Button>
        </span>
      </div>
      {group.issues.length === 0 ? (
        <p className="status-empty">No issues in this status.</p>
      ) : (
        <div className="issue-list">
          {group.issues.map((issue) => (
            <IssueRow
              issue={issue}
              isActive={issue.id === selectedIssueId}
              key={issue.id}
              onSelectIssue={onIssueSelect}
            />
          ))}
        </div>
      )}
    </section>
  )
}

function IssueRow({
  issue,
  isActive,
  onSelectIssue,
}: {
  issue: IssueSummary
  isActive: boolean
  onSelectIssue: (issueId: string) => void
}) {
  return (
    <button
      className="issue-row"
      data-active={isActive}
      type="button"
      onClick={() => onSelectIssue(issue.id)}
    >
      <span className="issue-row-status">
        <StatusIcon status={issue.status} />
      </span>
      <span className="issue-row-main">
        <strong>{issue.title}</strong>
        {issue.description ? <small>{issue.description}</small> : null}
      </span>
      <span className="issue-row-meta">
        <PriorityBadge priority={issue.priority} />
        <span className="issue-comment-count">
          <MessageSquare aria-hidden="true" />
          {issue.commentCount ?? 0}
        </span>
        <span>{formatDate(issue.updatedAt)}</span>
      </span>
    </button>
  )
}

function IssueDetailView({
  issue,
  issueDetail,
  selectedProject,
  currentWorkspace,
  currentUser,
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
  onBackToProject,
  onUpdateIssue,
  isUpdatingIssue,
  updateIssueError,
  onResetUpdateIssueError,
}: {
  issue: IssueSummary | IssueDetail | null
  issueDetail: IssueDetail | undefined
  selectedProject: Project | null
  currentWorkspace: AuthWorkspace | null
  currentUser: AuthUser | null
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
  onBackToProject: () => void
  onUpdateIssue: (request: UpdateIssueRequest) => Promise<void>
  isUpdatingIssue: boolean
  updateIssueError: Error | null
  onResetUpdateIssueError: () => void
}) {
  const comments = issueDetail?.comments ?? []
  const [editingIssueId, setEditingIssueId] = useState<string | null>(null)
  const [draftIssueTitle, setDraftIssueTitle] = useState(issue?.title ?? '')
  const [draftIssueDescription, setDraftIssueDescription] = useState(issue?.description ?? '')
  const [issueContentError, setIssueContentError] = useState('')
  const issueId = issue?.id ?? null
  const isEditingIssueContent = Boolean(issueId && editingIssueId === issueId)

  async function handleSaveIssueContent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const title = draftIssueTitle.trim()
    if (!title) {
      setIssueContentError('Title is required.')
      return
    }

    setIssueContentError('')

    try {
      await onUpdateIssue({
        title,
        description: draftIssueDescription.trim() || null,
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
    setIssueContentError('')
    setDraftIssueTitle(issue.title)
    setDraftIssueDescription(issue.description ?? '')
    setEditingIssueId(issue.id)
  }

  function cancelIssueContentEdit() {
    setIssueContentError('')
    setDraftIssueTitle(issue?.title ?? '')
    setDraftIssueDescription(issue?.description ?? '')
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
              <form className="issue-edit-form" onSubmit={handleSaveIssueContent}>
                <input
                  autoFocus
                  className="issue-title-input"
                  value={draftIssueTitle}
                  onChange={(event) => setDraftIssueTitle(event.target.value)}
                  disabled={isUpdatingIssue}
                  aria-label="Issue title"
                />
                <textarea
                  className="issue-description-input"
                  value={draftIssueDescription}
                  onChange={(event) => setDraftIssueDescription(event.target.value)}
                  disabled={isUpdatingIssue}
                  aria-label="Issue description"
                  placeholder="Add description..."
                  rows={5}
                />
                {issueContentError ? <InlineNotice tone="warning">{issueContentError}</InlineNotice> : null}
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
            <form className="comment-form" onSubmit={onSubmitComment}>
              <label className="app-field">
                Add comment
                <textarea
                  name="commentBody"
                  placeholder="Leave a comment..."
                  rows={4}
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
                Comment
              </Button>
            </form>
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
          currentUser={currentUser}
          onUpdateIssue={onUpdateIssue}
          isUpdatingIssue={isUpdatingIssue}
          updateIssueError={updateIssueError}
        />
      </div>
    </div>
  )
}

function IssuePropertiesPanel({
  issue,
  selectedProject,
  currentUser,
  onUpdateIssue,
  isUpdatingIssue,
  updateIssueError,
}: {
  issue: IssueSummary | IssueDetail
  selectedProject: Project | null
  currentUser: AuthUser | null
  onUpdateIssue: (request: UpdateIssueRequest) => Promise<void>
  isUpdatingIssue: boolean
  updateIssueError: Error | null
}) {
  const actor = issue.creator ?? issue.reporter ?? null

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
            value={issue.status}
            onChange={(event) => {
              void onUpdateIssue({ status: event.target.value as IssueStatus }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          >
            {getStatusOptions(issue.status).map((status) => (
              <option key={status} value={status}>
                {formatStatus(status)}
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
        {updateIssueError ? (
          <InlineNotice tone="warning">
            {getErrorMessage(updateIssueError)}
          </InlineNotice>
        ) : null}
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

function WorkspaceMismatchState({
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

function CreateProjectDialog({
  isOpen,
  projectName,
  projectDescription,
  onProjectNameChange,
  onProjectDescriptionChange,
  onSubmit,
  onClose,
  canCreateProject,
  isSubmitting,
  error,
}: {
  isOpen: boolean
  projectName: string
  projectDescription: string
  onProjectNameChange: (name: string) => void
  onProjectDescriptionChange: (description: string) => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  onClose: () => void
  canCreateProject: boolean
  isSubmitting: boolean
  error: Error | null
}) {
  if (!isOpen) {
    return null
  }

  return (
    <ModalShell title="Create project" eyebrow="Project" onClose={onClose}>
      <form className="modal-form" onSubmit={onSubmit}>
        <label className="app-field">
          Name
          <input
            autoFocus
            name="projectName"
            placeholder="Mobile launch"
            value={projectName}
            onChange={(event) => onProjectNameChange(event.target.value)}
            disabled={!canCreateProject}
          />
        </label>
        <label className="app-field">
          Description
          <textarea
            name="projectDescription"
            placeholder="Optional project notes"
            rows={4}
            value={projectDescription}
            onChange={(event) => onProjectDescriptionChange(event.target.value)}
            disabled={!canCreateProject}
          />
        </label>
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

function CreateIssueDialog({
  isOpen,
  selectedProject,
  issueTitle,
  issueDescription,
  issueStatus,
  issuePriority,
  onIssueTitleChange,
  onIssueDescriptionChange,
  onIssueStatusChange,
  onIssuePriorityChange,
  onSubmit,
  onClose,
  isSubmitting,
  error,
}: {
  isOpen: boolean
  selectedProject: Project | null
  issueTitle: string
  issueDescription: string
  issueStatus: IssueStatus
  issuePriority: IssuePriority | ''
  onIssueTitleChange: (title: string) => void
  onIssueDescriptionChange: (description: string) => void
  onIssueStatusChange: (status: IssueStatus) => void
  onIssuePriorityChange: (priority: IssuePriority | '') => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  onClose: () => void
  isSubmitting: boolean
  error: Error | null
}) {
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
      <form className="issue-modal-form" onSubmit={onSubmit}>
        <input
          autoFocus
          className="issue-modal-title"
          name="issueTitle"
          placeholder="Issue title"
          value={issueTitle}
          onChange={(event) => onIssueTitleChange(event.target.value)}
          disabled={!selectedProject}
        />
        <textarea
          className="issue-modal-description"
          name="issueDescription"
          placeholder="Add description..."
          rows={5}
          value={issueDescription}
          onChange={(event) => onIssueDescriptionChange(event.target.value)}
          disabled={!selectedProject}
        />
        <div className="issue-modal-chip-row" aria-label="Issue properties">
          <label className="issue-modal-chip issue-modal-chip-select">
            <StatusIcon status={issueStatus} />
            <select
              value={issueStatus}
              onChange={(event) => onIssueStatusChange(event.target.value as IssueStatus)}
              disabled={!selectedProject}
              aria-label="Status"
            >
              {ISSUE_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {STATUS_LABELS[status]}
                </option>
              ))}
            </select>
          </label>
          <label className="issue-modal-chip issue-modal-chip-select">
            <Flag aria-hidden="true" />
            <select
              value={issuePriority}
              onChange={(event) => onIssuePriorityChange(event.target.value as IssuePriority | '')}
              disabled={!selectedProject}
              aria-label="Priority"
            >
              <option value="">Priority</option>
              {ISSUE_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {PRIORITY_LABELS[priority]}
                </option>
              ))}
            </select>
          </label>
          <span className="issue-modal-chip">
            <FolderKanban aria-hidden="true" />
            {selectedProject?.name ?? 'Project'}
          </span>
          <span className="issue-modal-chip issue-modal-chip-muted">
            <MoreHorizontal aria-hidden="true" />
          </span>
        </div>
        {error ? <ErrorState error={error} /> : null}
        <div className="issue-modal-footer">
          <span className="app-state">
            Status is sent with the issue creation request.
          </span>
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

function ModalShell({
  title,
  eyebrow,
  children,
  onClose,
  variant = 'default',
}: {
  title: string
  eyebrow: string
  children: ReactNode
  onClose: () => void
  variant?: 'default' | 'issue'
}) {
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <section className="modal-panel" data-variant={variant} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <div>
            <p className="breadcrumb-line">{eyebrow}</p>
            <h2>{title}</h2>
          </div>
          <Button type="button" variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close">
            <X aria-hidden="true" />
          </Button>
        </header>
        {children}
      </section>
    </div>
  )
}

function BreadcrumbLine({ items }: { items: string[] }) {
  return (
    <p className="breadcrumb-line">
      {items.map((item, index) => (
        <span key={`${item}-${index}`}>
          {index > 0 ? <span aria-hidden="true">/</span> : null}
          {item}
        </span>
      ))}
    </p>
  )
}

function StatusIcon({ status }: { status: IssueStatus }) {
  if (status === 'DONE') {
    return <CheckCircle2 aria-hidden="true" className="status-icon status-icon-done" />
  }

  if (status === 'IN_PROGRESS') {
    return <CircleDot aria-hidden="true" className="status-icon status-icon-progress" />
  }

  if (status === 'ARCHIVED') {
    return <Circle aria-hidden="true" className="status-icon status-icon-muted" />
  }

  return <Circle aria-hidden="true" className="status-icon" />
}

function PriorityBadge({ priority }: { priority?: IssuePriority | null }) {
  if (!priority) {
    return <span className="priority-badge priority-badge-empty">No priority</span>
  }

  return (
    <span className="priority-badge">
      <Flag aria-hidden="true" />
      {formatPriority(priority)}
    </span>
  )
}

function InlineState({ children }: { children: ReactNode }) {
  return <p className="app-state">{children}</p>
}

function InlineNotice({
  children,
  tone = 'default',
}: {
  children: ReactNode
  tone?: 'default' | 'warning'
}) {
  return (
    <p className="inline-notice" data-tone={tone}>
      {children}
    </p>
  )
}

function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty-state">
      <Building2 aria-hidden="true" />
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  )
}

function ErrorState({ error }: { error: Error }) {
  return <p className="app-error">{getErrorMessage(error)}</p>
}

function groupIssuesByStatus(issues: IssueSummary[]) {
  const grouped = new Map<IssueStatus, IssueSummary[]>()

  ISSUE_STATUSES.forEach((status) => grouped.set(status, []))
  issues.forEach((issue) => {
    const group = grouped.get(issue.status) ?? []
    group.push(issue)
    grouped.set(issue.status, group)
  })

  return Array.from(grouped.entries()).map(([status, statusIssues]) => ({
    status,
    label: formatStatus(status),
    issues: statusIssues,
  }))
}

function getStatusOptions(currentStatus: IssueStatus) {
  const options = new Set<IssueStatus>(ISSUE_STATUSES)
  options.add(currentStatus)
  return Array.from(options)
}

function projectPath(workspaceId: string, projectId: string) {
  return `/app/workspaces/${workspaceId}/projects/${projectId}`
}

function issuePath(workspaceId: string, projectId: string, issueId: string) {
  return `${projectPath(workspaceId, projectId)}/issues/${issueId}`
}

function getInitials(value: string) {
  return value
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

function getErrorMessage(error: Error) {
  if (error instanceof ApiError) {
    return error.message
  }

  return 'Unable to load this workspace data.'
}

function formatStatus(status: string) {
  if (isKnownStatus(status)) {
    return STATUS_LABELS[status]
  }

  return status
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

function formatPriority(priority: string) {
  if (isKnownPriority(priority)) {
    return PRIORITY_LABELS[priority]
  }

  return formatStatus(priority)
}

function isKnownStatus(status: string): status is (typeof ISSUE_STATUSES)[number] {
  return ISSUE_STATUSES.includes(status as (typeof ISSUE_STATUSES)[number])
}

function isKnownPriority(priority: string): priority is (typeof ISSUE_PRIORITIES)[number] {
  return ISSUE_PRIORITIES.includes(priority as (typeof ISSUE_PRIORITIES)[number])
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
