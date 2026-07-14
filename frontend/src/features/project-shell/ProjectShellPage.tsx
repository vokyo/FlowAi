import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import {
  Menu,
} from 'lucide-react'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import {
  type AnalyticsRangeDays,
} from '@/analytics/analytics-api'
import { useProjectAnalyticsQuery } from '@/analytics/useProjectAnalyticsQuery'
import { Button } from '@/components/ui/button'
import {
  type IssuePriority,
  type ProjectMember,
  type ProjectWorkflowState,
  type UpdateIssueRequest,
} from '@/work/work-api'
import { WorkspaceSidebar } from '@/workspace/WorkspaceSidebar'
import { useInvitationMutations } from '@/features/invitations/useInvitationMutations'
import { useInvitationQuery } from '@/features/invitations/useInvitationQuery'
import { useProjectMemberMutations } from '@/features/project-members/useProjectMemberMutations'
import { useProjectMemberQueries } from '@/features/project-members/useProjectMemberQueries'
import { useIssueDetailMutations } from '@/features/issue-detail/useIssueDetailMutations'
import { useIssueDetailQueries } from '@/features/issue-detail/useIssueDetailQueries'
import { useIssueMutations } from '@/features/issue-list/useIssueMutations'
import { useIssueListQueries } from '@/features/issue-list/useIssueListQueries'
import { useProjectLabelsQuery } from '@/features/issue-list/useProjectLabelsQuery'
import { buildIssueListFilterState } from '@/features/issue-list/issue-filter-utils'
import { useBoardMutations } from '@/features/board/useBoardMutations'
import { useBoardQueries } from '@/features/board/useBoardQueries'
import { useProjectShellMutations } from '@/features/project-shell/useProjectShellMutations'
import { useProjectShellQueries } from '@/features/project-shell/useProjectShellQueries'
import {
  CreateProjectDialog,
  CreateWorkspaceDialog,
  WorkspaceMismatchState,
} from '@/features/project-shell/ProjectShellStates'
import { InlineState } from '@/features/project-shell/feature-ui'
import {
  type AddProjectMemberFormValues,
  type CommentFormValues,
  type CreateIssueDialogSeed,
  type CreateIssueFormValues,
  type CreateProjectFormValues,
  type CreateProjectLabelFormValues,
  type CreateProjectWorkflowStateFormValues,
  type KanbanReorder,
  type QuickCreateIssueMutationVariables,
  type UpdateProjectWorkflowStateFormValues,
} from '@/features/project-shell/project-model'
import {
  analyticsRangeFromSearchParams,
  analyticsSearchParams,
  boardIssueViewFromSearchParams,
  isProjectAnalyticsPath,
  issuePath,
  issueViewModeFromSearchParams,
  issueViewSearchParams,
  normalizeAppSearchParams,
  pathWithSearchParams,
  projectAnalyticsPath,
  projectPath,
  workViewSearchParams,
  type IssueViewMode,
} from '@/features/project-shell/route-utils'
import {
  defaultWorkflowStateIdForStatus,
  groupIssuesByWorkflowState,
  type BoardIssueView,
  type IssueWorkflowFilter,
} from '@/work/board-utils'

const ProjectAnalyticsView = lazy(() =>
  import('@/analytics/ProjectAnalyticsView').then((module) => ({
    default: module.ProjectAnalyticsView,
  })),
)

const IssueListFeature = lazy(() =>
  import('@/features/issue-list/IssueListFeature').then((module) => ({
    default: module.IssueListFeature,
  })),
)

const IssueDetailFeature = lazy(() =>
  import('@/features/issue-detail/IssueDetailFeature').then((module) => ({
    default: module.IssueDetailFeature,
  })),
)

const WorkspaceInvitationsDialog = lazy(() =>
  import('@/features/invitations/WorkspaceInvitationsDialog').then((module) => ({
    default: module.WorkspaceInvitationsDialog,
  })),
)

const ProjectMembersDialog = lazy(() =>
  import('@/features/project-members/ProjectMembersDialog').then((module) => ({
    default: module.ProjectMembersDialog,
  })),
)

const ProjectWorkflowDialog = lazy(() =>
  import('@/features/board/ProjectWorkflowDialog').then((module) => ({
    default: module.ProjectWorkflowDialog,
  })),
)

const CreateIssueDialog = lazy(() =>
  import('@/features/issue-list/CreateIssueDialog').then((module) => ({
    default: module.CreateIssueDialog,
  })),
)

type AppPageProps = {
  onSignOut: () => void
  onSessionChanged: () => void
}

type AppRouteParams = {
  workspaceId?: string
  projectId?: string
  issueId?: string
}

type CreateDialog = 'project' | 'issue' | null


export function ProjectShellPage({ onSignOut, onSessionChanged }: AppPageProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const {
    workspaceId: routeWorkspaceId,
    projectId: routeProjectId,
    issueId: routeIssueId,
  } = useParams<AppRouteParams>()
  const [activeCreateDialog, setActiveCreateDialog] = useState<CreateDialog>(null)
  const [areProjectsOpen, setAreProjectsOpen] = useState(true)
  const [createIssueDefaultWorkflowStateId, setCreateIssueDefaultWorkflowStateId] = useState('')
  const [createIssueDefaultTitle, setCreateIssueDefaultTitle] = useState('')
  const [createIssueDefaultAssigneeUserId, setCreateIssueDefaultAssigneeUserId] = useState('')
  const [issueSearchQuery, setIssueSearchQuery] = useState('')
  const [issueWorkflowFilter, setIssueWorkflowFilter] = useState<IssueWorkflowFilter>('ACTIVE')
  const [issuePriorityFilter, setIssuePriorityFilter] = useState<IssuePriority | ''>('')
  const [issueLabelFilter, setIssueLabelFilter] = useState('')
  const [issueAssigneeFilter, setIssueAssigneeFilter] = useState('')
  const [isProjectMembersDialogOpen, setIsProjectMembersDialogOpen] = useState(false)
  const [isProjectWorkflowDialogOpen, setIsProjectWorkflowDialogOpen] = useState(false)
  const [isWorkspaceMenuOpen, setIsWorkspaceMenuOpen] = useState(false)
  const [isMobileSidebarOpen, setIsMobileSidebarOpen] = useState(false)
  const [isCreateWorkspaceDialogOpen, setIsCreateWorkspaceDialogOpen] = useState(false)
  const [isWorkspaceInvitationsDialogOpen, setIsWorkspaceInvitationsDialogOpen] = useState(false)
  const [latestInvitationLink, setLatestInvitationLink] = useState<string | null>(null)
  const isAnalyticsRoute = isProjectAnalyticsPath(location.pathname)
  const issueViewMode = issueViewModeFromSearchParams(searchParams)
  const boardIssueView = boardIssueViewFromSearchParams(searchParams)
  const analyticsRangeDays = analyticsRangeFromSearchParams(searchParams)
  const normalizedWorkViewSearchParams = issueViewSearchParams(searchParams)
  const normalizedAppSearchParams = normalizeAppSearchParams(searchParams, isAnalyticsRoute)
  const rawSearchString = searchParams.toString()
  const normalizedSearchString = normalizedAppSearchParams.toString()

  useEffect(() => {
    if (rawSearchString !== normalizedSearchString) {
      setSearchParams(new URLSearchParams(normalizedSearchString), { replace: true })
    }
  }, [normalizedSearchString, rawSearchString, setSearchParams])

  const {
    currentSessionQuery,
    currentWorkspace,
    currentWorkspaceId,
    currentUser,
    routeMatchesCurrentWorkspace,
    canLoadCurrentWorkspace,
    workspacesQuery,
    workspaces,
    projectsQuery,
    projects,
    selectedProject,
    selectedProjectId,
  } = useProjectShellQueries({ routeWorkspaceId, routeProjectId })
  const canManageWorkspaceInvitations =
    currentWorkspace?.role === 'OWNER' || currentWorkspace?.role === 'ADMIN'

  const {
    projectMembersQuery,
    workspaceMembersQuery,
    projectMembers,
    workspaceMembers,
    activeProjectMembers,
    canManageProjectMembers,
    addableWorkspaceMembers,
  } = useProjectMemberQueries({
    workspaceId: currentWorkspaceId,
    projectId: selectedProjectId,
    currentUserId: currentUser?.id ?? null,
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    loadWorkspaceMembers: isProjectMembersDialogOpen,
  })

  const { labels: projectLabels } =
    useProjectLabelsQuery(
      selectedProjectId,
      Boolean(canLoadCurrentWorkspace && selectedProjectId),
    )

  const {
    workflowStatesQuery: projectWorkflowStatesQuery,
    workflowStates: projectWorkflowStates,
    boardQuery: projectBoardQuery,
    board: projectBoard,
    mergeBoardColumnPage,
  } = useBoardQueries({
    workspaceId: currentWorkspaceId,
    projectId: selectedProjectId,
    metadataEnabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    boardEnabled: Boolean(
      canLoadCurrentWorkspace &&
      selectedProjectId &&
      issueViewMode === 'BOARD' &&
      !isAnalyticsRoute
    ),
  })

  const projectAnalyticsQuery = useProjectAnalyticsQuery({
    workspaceId: currentWorkspaceId,
    projectId: selectedProjectId,
    rangeDays: analyticsRangeDays,
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId && isAnalyticsRoute),
  })

  const { query: workspaceInvitationsQuery, invitations: workspaceInvitations } =
    useInvitationQuery({
      workspaceId: currentWorkspaceId,
      enabled: Boolean(
      canLoadCurrentWorkspace &&
      canManageWorkspaceInvitations &&
      isWorkspaceInvitationsDialogOpen
      ),
    })

  const {
    filters: issueFilters,
    filterKey: issueFilterKey,
    hasFilters: hasIssueFilters,
  } = buildIssueListFilterState({
    searchQuery: issueSearchQuery,
    workflowFilter: issueWorkflowFilter,
    priorityFilter: issuePriorityFilter,
    labelFilter: issueLabelFilter,
    assigneeFilter: issueAssigneeFilter,
  })

  const { issuesQuery, issues } = useIssueListQueries({
    workspaceId: currentWorkspaceId,
    projectId: selectedProjectId,
    filters: issueFilters,
    filterKey: issueFilterKey,
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId && !isAnalyticsRoute),
  })
  const issueGroups = useMemo(
    () => groupIssuesByWorkflowState(issues, projectWorkflowStates, issueWorkflowFilter),
    [issueWorkflowFilter, issues, projectWorkflowStates],
  )
  const selectedIssueSummary = routeIssueId
    ? issues.find((issue) => issue.id === routeIssueId) ?? null
    : null

  const {
    issueQuery: issueDetailQuery,
    commentsQuery,
    activitiesQuery,
    comments,
    activities,
  } = useIssueDetailQueries({
    workspaceId: currentWorkspaceId,
    issueId: routeIssueId,
    enabled: Boolean(canLoadCurrentWorkspace && selectedProject && routeIssueId),
  })

  useEffect(() => {
    if (!currentWorkspaceId || !canLoadCurrentWorkspace || !projectsQuery.isSuccess) {
      return
    }

    if (projects.length === 0) {
      if (routeProjectId || routeIssueId) {
        navigate(pathWithSearchParams('/app', normalizedSearchString), { replace: true })
      }
      return
    }

    const hasRouteProject = Boolean(
      routeProjectId && projects.some((project) => project.id === routeProjectId),
    )

    if (!routeProjectId || !hasRouteProject) {
      const firstProjectPath = isAnalyticsRoute
        ? projectAnalyticsPath(currentWorkspaceId, projects[0].id)
        : projectPath(currentWorkspaceId, projects[0].id)
      navigate(
        pathWithSearchParams(
          firstProjectPath,
          normalizedSearchString,
        ),
        { replace: true },
      )
    }
  }, [
    canLoadCurrentWorkspace,
    currentWorkspaceId,
    isAnalyticsRoute,
    navigate,
    normalizedSearchString,
    projects,
    projectsQuery.isSuccess,
    routeIssueId,
    routeProjectId,
  ])

  const {
    switchWorkspaceMutation,
    createWorkspaceMutation,
    createProjectMutation,
  } = useProjectShellMutations({
    workspaceId: currentWorkspaceId,
    workViewSearchParams: normalizedWorkViewSearchParams,
    onSessionChanged,
    onWorkspaceCreated: () => setIsCreateWorkspaceDialogOpen(false),
    onProjectCreated: () => setActiveCreateDialog(null),
  })

  const {
    createInvitationMutation: createWorkspaceInvitationMutation,
    reissueInvitationMutation: reissueWorkspaceInvitationMutation,
    revokeInvitationMutation: revokeWorkspaceInvitationMutation,
  } = useInvitationMutations({
    workspaceId: currentWorkspaceId,
    onInvitationLink: setLatestInvitationLink,
  })

  const { createIssueMutation, createProjectLabelMutation } = useIssueMutations({
    workspaceId: currentWorkspaceId,
    onIssueCreated: (issue) => {
      setActiveCreateDialog(null)
      if (currentWorkspaceId) {
        navigate(
          pathWithSearchParams(
            issuePath(currentWorkspaceId, issue.projectId, issue.id),
            normalizedWorkViewSearchParams,
          ),
        )
      }
    },
  })

  const {
    quickCreateIssueMutation,
    reorderIssueMutation,
    createWorkflowStateMutation: createProjectWorkflowStateMutation,
    updateWorkflowStateMutation: updateProjectWorkflowStateMutation,
    reorderWorkflowStatesMutation: reorderProjectWorkflowStatesMutation,
  } = useBoardMutations(currentWorkspaceId)

  const { createCommentMutation, updateIssueMutation } =
    useIssueDetailMutations(currentWorkspaceId)

  const {
    addMemberMutation: addProjectMemberMutation,
    updateMemberMutation: updateProjectMemberMutation,
    removeMemberMutation: removeProjectMemberMutation,
    resetMemberMutations,
  } = useProjectMemberMutations({
    workspaceId: currentWorkspaceId,
    currentUserId: currentUser?.id ?? null,
    onCurrentUserRemoved: () => setIsProjectMembersDialogOpen(false),
  })

  const resetProjectMemberMutations = resetMemberMutations

  function openCreateProjectDialog() {
    createProjectMutation.reset()
    setActiveCreateDialog('project')
  }

  function handleWorkspaceSelect(workspaceId: string) {
    setIsWorkspaceMenuOpen(false)
    if (workspaceId === currentWorkspaceId) {
      return
    }
    switchWorkspaceMutation.reset()
    switchWorkspaceMutation.mutate(workspaceId)
  }

  function openCreateWorkspaceDialog() {
    setIsWorkspaceMenuOpen(false)
    createWorkspaceMutation.reset()
    setIsCreateWorkspaceDialogOpen(true)
  }

  function closeCreateWorkspaceDialog() {
    if (!createWorkspaceMutation.isPending) {
      setIsCreateWorkspaceDialogOpen(false)
    }
  }

  function openWorkspaceInvitationsDialog() {
    setIsWorkspaceMenuOpen(false)
    setLatestInvitationLink(null)
    createWorkspaceInvitationMutation.reset()
    reissueWorkspaceInvitationMutation.reset()
    revokeWorkspaceInvitationMutation.reset()
    setIsWorkspaceInvitationsDialogOpen(true)
  }

  function closeWorkspaceInvitationsDialog() {
    if (
      createWorkspaceInvitationMutation.isPending ||
      reissueWorkspaceInvitationMutation.isPending ||
      revokeWorkspaceInvitationMutation.isPending
    ) {
      return
    }
    setIsWorkspaceInvitationsDialogOpen(false)
    setLatestInvitationLink(null)
  }

  function openCreateIssueDialog(
    workflowState?: ProjectWorkflowState | null,
    seed: CreateIssueDialogSeed = {},
  ) {
    createIssueMutation.reset()
    setCreateIssueDefaultWorkflowStateId(
      workflowState?.id ?? defaultWorkflowStateIdForStatus(projectWorkflowStates, 'TODO'),
    )
    setCreateIssueDefaultTitle(seed.title ?? '')
    setCreateIssueDefaultAssigneeUserId(seed.assigneeUserId ?? '')
    createProjectLabelMutation.reset()
    setActiveCreateDialog('issue')
  }

  function closeCreateDialog() {
    if (
      createProjectMutation.isPending ||
      createIssueMutation.isPending ||
      createProjectLabelMutation.isPending
    ) {
      return
    }

    setActiveCreateDialog(null)
  }

  function openProjectMembersDialog() {
    resetProjectMemberMutations()
    setIsProjectMembersDialogOpen(true)
  }

  function closeProjectMembersDialog() {
    if (
      addProjectMemberMutation.isPending ||
      updateProjectMemberMutation.isPending ||
      removeProjectMemberMutation.isPending
    ) {
      return
    }

    setIsProjectMembersDialogOpen(false)
  }

  function openProjectWorkflowDialog() {
    createProjectWorkflowStateMutation.reset()
    updateProjectWorkflowStateMutation.reset()
    reorderProjectWorkflowStatesMutation.reset()
    setIsProjectWorkflowDialogOpen(true)
  }

  function closeProjectWorkflowDialog() {
    if (
      createProjectWorkflowStateMutation.isPending ||
      updateProjectWorkflowStateMutation.isPending ||
      reorderProjectWorkflowStatesMutation.isPending
    ) {
      return
    }

    setIsProjectWorkflowDialogOpen(false)
  }

  function clearIssueFilters() {
    setIssueSearchQuery('')
    setIssueWorkflowFilter('ACTIVE')
    setIssuePriorityFilter('')
    setIssueLabelFilter('')
    setIssueAssigneeFilter('')
  }

  function handleIssueViewModeChange(nextLayout: IssueViewMode) {
    setSearchParams(
      workViewSearchParams(searchParams, nextLayout, boardIssueView),
    )
  }

  function handleSidebarViewSelect(nextView: BoardIssueView) {
    if (!currentWorkspaceId || !selectedProjectId) {
      return
    }

    const nextSearchParams = workViewSearchParams(searchParams, 'BOARD', nextView)
    navigate(
      pathWithSearchParams(
        projectPath(currentWorkspaceId, selectedProjectId),
        nextSearchParams,
      ),
    )
  }

  function handleAnalyticsSelect() {
    if (!currentWorkspaceId || !selectedProjectId) {
      return
    }

    navigate(
      pathWithSearchParams(
        projectAnalyticsPath(currentWorkspaceId, selectedProjectId),
        analyticsSearchParams(searchParams, analyticsRangeDays),
      ),
    )
  }

  function handleAnalyticsRangeChange(nextRangeDays: AnalyticsRangeDays) {
    setSearchParams(analyticsSearchParams(searchParams, nextRangeDays))
  }

  function handleProjectSelect(projectId: string) {
    if (!currentWorkspaceId) {
      return
    }

    const nextPath = isAnalyticsRoute
      ? projectAnalyticsPath(currentWorkspaceId, projectId)
      : projectPath(currentWorkspaceId, projectId)
    const nextSearchParams = isAnalyticsRoute
      ? normalizedAppSearchParams
      : normalizedWorkViewSearchParams

    navigate(pathWithSearchParams(nextPath, nextSearchParams))
  }

  function handleIssueSelect(issueId: string) {
    if (!currentWorkspaceId || !selectedProjectId) {
      return
    }

    navigate(
      pathWithSearchParams(
        issuePath(currentWorkspaceId, selectedProjectId, issueId),
        normalizedWorkViewSearchParams,
      ),
    )
  }

  async function handleReorderIssue({
    optimisticBoard,
    ...request
  }: KanbanReorder) {
    if (!selectedProjectId) {
      return
    }

    reorderIssueMutation.reset()
    await reorderIssueMutation.mutateAsync({
      projectId: selectedProjectId,
      request,
      optimisticBoard,
    })
  }

  async function handleQuickCreateIssue({
    title,
    workflowStateId,
    assigneeUserId,
  }: Omit<QuickCreateIssueMutationVariables, 'projectId'>) {
    const trimmedTitle = title.trim()
    if (!selectedProjectId || !trimmedTitle) {
      return null
    }

    quickCreateIssueMutation.reset()
    return quickCreateIssueMutation.mutateAsync({
      projectId: selectedProjectId,
      title: trimmedTitle,
      workflowStateId,
      assigneeUserId,
    })
  }

  function handleCreateProject(values: CreateProjectFormValues) {
    const name = values.name.trim()
    if (!name || !canLoadCurrentWorkspace) {
      return
    }

    createProjectMutation.mutate({
      name,
      description: values.description.trim(),
    })
  }

  function handleCreateIssue(values: CreateIssueFormValues) {
    const title = values.title.trim()
    if (!selectedProjectId || !title) {
      return
    }

    createIssueMutation.mutate({
      projectId: selectedProjectId,
      title,
      description: values.description.trim() || undefined,
      workflowStateId: values.workflowStateId || undefined,
      priority: values.priority || undefined,
      labelIds: values.labelIds,
      assigneeUserId: values.assigneeUserId || undefined,
      dueDate: values.dueDate || undefined,
    })
  }

  async function handleCreateIssueLabel(values: CreateProjectLabelFormValues) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) {
      return null
    }

    createProjectLabelMutation.reset()
    return createProjectLabelMutation.mutateAsync({
      projectId: selectedProjectId,
      name,
      color: values.color,
    })
  }

  async function handleCreateProjectWorkflowState(values: CreateProjectWorkflowStateFormValues) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) {
      return null
    }

    createProjectWorkflowStateMutation.reset()
    return createProjectWorkflowStateMutation.mutateAsync({
      projectId: selectedProjectId,
      name,
      category: values.category,
    })
  }

  async function handleUpdateProjectWorkflowState(
    workflowStateId: string,
    values: UpdateProjectWorkflowStateFormValues,
  ) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) {
      return null
    }

    updateProjectWorkflowStateMutation.reset()
    return updateProjectWorkflowStateMutation.mutateAsync({
      projectId: selectedProjectId,
      workflowStateId,
      values: {
        name,
        category: values.category,
      },
    })
  }

  async function handleReorderProjectWorkflowState(workflowStateId: string, direction: -1 | 1) {
    if (!selectedProjectId) {
      return
    }

    const currentIndex = projectWorkflowStates.findIndex(
      (workflowState) => workflowState.id === workflowStateId,
    )
    const nextIndex = currentIndex + direction
    if (
      currentIndex < 0 ||
      nextIndex < 0 ||
      nextIndex >= projectWorkflowStates.length
    ) {
      return
    }

    const workflowStateIds = projectWorkflowStates.map((workflowState) => workflowState.id)
    const nextWorkflowStateId = workflowStateIds[nextIndex]
    workflowStateIds[nextIndex] = workflowStateIds[currentIndex]
    workflowStateIds[currentIndex] = nextWorkflowStateId

    reorderProjectWorkflowStatesMutation.reset()
    await reorderProjectWorkflowStatesMutation.mutateAsync({
      projectId: selectedProjectId,
      workflowStateIds,
    })
  }

  async function handleAddProjectMember(values: AddProjectMemberFormValues) {
    const userId = values.userId.trim()
    if (!selectedProjectId || !userId) {
      return
    }

    resetProjectMemberMutations()
    await addProjectMemberMutation.mutateAsync({
      projectId: selectedProjectId,
      userId,
    })
  }

  async function handleUpdateProjectMemberRole(
    memberId: string,
    role: 'OWNER' | 'MEMBER',
  ) {
    if (!selectedProjectId) {
      return
    }

    resetProjectMemberMutations()
    await updateProjectMemberMutation.mutateAsync({
      projectId: selectedProjectId,
      memberId,
      role,
    })
  }

  async function handleRemoveProjectMember(member: ProjectMember) {
    if (!selectedProjectId) {
      return
    }

    const memberName = member.displayName || member.email
    const isRemovingSelf = member.userId === currentUser?.id
    const confirmed = window.confirm(
      isRemovingSelf
        ? 'Remove yourself from this project? You will lose access immediately.'
        : `Remove ${memberName} from this project?`,
    )
    if (!confirmed) {
      return
    }

    resetProjectMemberMutations()
    await removeProjectMemberMutation.mutateAsync({
      projectId: selectedProjectId,
      memberId: member.id,
      userId: member.userId,
    })
  }

  async function handleCreateComment(values: CommentFormValues) {
    const body = values.body.trim()
    if (!routeIssueId || !selectedProjectId || !body) {
      return
    }

    await createCommentMutation.mutateAsync({
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

  async function handleArchiveIssue() {
    if (!routeIssueId) {
      return
    }

    const confirmed = window.confirm(
      'Archive this issue? It will be hidden from the active issue list.',
    )
    if (!confirmed) {
      return
    }

    try {
      await handleUpdateIssue({ status: 'ARCHIVED' })
    } catch {
      // The shared update error is rendered in the detail panel.
    }
  }

  const activeIssue = issueDetailQuery.data ?? selectedIssueSummary
  const isIssueDetailRoute = Boolean(routeIssueId)

  return (
    <main className="app-shell">
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="mobile-sidebar-trigger"
        aria-label="Open navigation"
        aria-expanded={isMobileSidebarOpen}
        onClick={() => setIsMobileSidebarOpen(true)}
      >
        <Menu aria-hidden="true" />
      </Button>
      {isMobileSidebarOpen ? (
        <button
          className="mobile-sidebar-backdrop"
          type="button"
          aria-label="Close navigation"
          onClick={() => setIsMobileSidebarOpen(false)}
        />
      ) : null}
      <WorkspaceSidebar
        currentUser={currentUser}
        currentWorkspace={currentWorkspace}
        workspaces={workspaces}
        isLoadingWorkspace={currentSessionQuery.isLoading}
        isLoadingWorkspaces={workspacesQuery.isLoading}
        workspacesError={workspacesQuery.error ?? switchWorkspaceMutation.error}
        isWorkspaceMenuOpen={isWorkspaceMenuOpen}
        isSwitchingWorkspace={switchWorkspaceMutation.isPending}
        onToggleWorkspaceMenu={() => setIsWorkspaceMenuOpen((isOpen) => !isOpen)}
        onCloseWorkspaceMenu={() => setIsWorkspaceMenuOpen(false)}
        onWorkspaceSelect={handleWorkspaceSelect}
        onOpenCreateWorkspace={openCreateWorkspaceDialog}
        onOpenWorkspaceInvitations={openWorkspaceInvitationsDialog}
        canManageWorkspaceInvitations={canManageWorkspaceInvitations}
        projects={projects}
        selectedProjectId={selectedProjectId}
        isAnalyticsRoute={isAnalyticsRoute}
        issueViewMode={issueViewMode}
        boardIssueView={boardIssueView}
        isLoadingProjects={projectsQuery.isLoading}
        projectsError={projectsQuery.error}
        areProjectsOpen={areProjectsOpen}
        onToggleProjects={() => setAreProjectsOpen((isOpen) => !isOpen)}
        onOpenCreateProject={openCreateProjectDialog}
        canCreateProject={canLoadCurrentWorkspace && currentWorkspace?.role !== 'GUEST'}
        canSelectViews={Boolean(selectedProjectId)}
        onViewSelect={handleSidebarViewSelect}
        onAnalyticsSelect={handleAnalyticsSelect}
        onProjectSelect={handleProjectSelect}
        onSignOut={onSignOut}
        isMobileOpen={isMobileSidebarOpen}
        onMobileClose={() => setIsMobileSidebarOpen(false)}
      />

      <section className="app-main">
        {!routeMatchesCurrentWorkspace ? (
          <WorkspaceMismatchState
            currentWorkspace={currentWorkspace}
            routeWorkspaceId={routeWorkspaceId}
          />
        ) : isIssueDetailRoute ? (
          <Suspense fallback={<div className="content-page"><InlineState>Loading issue detail.</InlineState></div>}>
          <IssueDetailFeature
            issue={activeIssue}
            selectedProject={selectedProject}
            projectMembers={activeProjectMembers}
            projectLabels={projectLabels}
            projectWorkflowStates={projectWorkflowStates}
            currentWorkspace={currentWorkspace}
            currentUser={currentUser}
            comments={comments}
            activities={activities}
            isLoadingIssue={issueDetailQuery.isLoading}
            issueError={issueDetailQuery.error}
            isLoadingComments={commentsQuery.isLoading}
            commentsError={commentsQuery.error}
            hasMoreComments={commentsQuery.hasNextPage}
            isLoadingMoreComments={commentsQuery.isFetchingNextPage}
            onLoadMoreComments={() => void commentsQuery.fetchNextPage()}
            isLoadingActivities={activitiesQuery.isLoading}
            activitiesError={activitiesQuery.error}
            hasMoreActivities={activitiesQuery.hasNextPage}
            isLoadingMoreActivities={activitiesQuery.isFetchingNextPage}
            onLoadMoreActivities={() => void activitiesQuery.fetchNextPage()}
            onSubmitComment={handleCreateComment}
            isSubmittingComment={createCommentMutation.isPending}
            commentError={createCommentMutation.error}
            onBackToProject={() => {
              if (currentWorkspaceId && selectedProjectId) {
                navigate(
                  pathWithSearchParams(
                    projectPath(currentWorkspaceId, selectedProjectId),
                    normalizedWorkViewSearchParams,
                  ),
                )
              }
            }}
            onUpdateIssue={handleUpdateIssue}
            onArchiveIssue={handleArchiveIssue}
            isUpdatingIssue={updateIssueMutation.isPending}
            updateIssueError={updateIssueMutation.error}
            onResetUpdateIssueError={() => updateIssueMutation.reset()}
          />
          </Suspense>
        ) : isAnalyticsRoute ? (
          <Suspense fallback={<InlineState>Loading analytics.</InlineState>}>
            <ProjectAnalyticsView
              project={selectedProject}
              overview={projectAnalyticsQuery.data ?? null}
              rangeDays={analyticsRangeDays}
              isLoading={projectAnalyticsQuery.isLoading}
              error={projectAnalyticsQuery.error}
              onRangeChange={handleAnalyticsRangeChange}
            />
          </Suspense>
        ) : (
          <Suspense fallback={<div className="content-page"><InlineState>Loading issues.</InlineState></div>}>
          <IssueListFeature
            currentWorkspace={currentWorkspace}
            currentUser={currentUser}
            selectedProject={selectedProject}
            projectMembers={projectMembers}
            projectLabels={projectLabels}
            projectWorkflowStates={projectWorkflowStates}
            projectBoard={projectBoard}
            issues={issues}
            issueGroups={issueGroups}
            issueViewMode={issueViewMode}
            boardIssueView={boardIssueView}
            selectedIssueId={routeIssueId ?? null}
            isLoadingIssues={issuesQuery.isLoading}
            issuesError={issuesQuery.error}
            hasMoreIssues={issuesQuery.hasNextPage}
            isLoadingMoreIssues={issuesQuery.isFetchingNextPage}
            onLoadMoreIssues={() => void issuesQuery.fetchNextPage()}
            isLoadingProjectBoard={projectBoardQuery.isLoading}
            projectBoardError={projectBoardQuery.error}
            onBoardColumnPageLoaded={mergeBoardColumnPage}
            reorderIssueError={reorderIssueMutation.error}
            isReorderingIssue={reorderIssueMutation.isPending}
            quickCreateIssueError={quickCreateIssueMutation.error}
            isQuickCreatingIssue={quickCreateIssueMutation.isPending}
            canUseQuickCreateShortcut={
              activeCreateDialog === null &&
              !isProjectMembersDialogOpen &&
              !isProjectWorkflowDialogOpen &&
              !isCreateWorkspaceDialogOpen &&
              !isWorkspaceInvitationsDialogOpen
            }
            isLoadingProjectMembers={projectMembersQuery.isLoading}
            isLoadingProjects={projectsQuery.isLoading}
            issueSearchQuery={issueSearchQuery}
            issueWorkflowFilter={issueWorkflowFilter}
            issuePriorityFilter={issuePriorityFilter}
            issueLabelFilter={issueLabelFilter}
            issueAssigneeFilter={issueAssigneeFilter}
            hasIssueFilters={hasIssueFilters}
            onIssueSearchQueryChange={setIssueSearchQuery}
            onIssueWorkflowFilterChange={setIssueWorkflowFilter}
            onIssuePriorityFilterChange={setIssuePriorityFilter}
            onIssueLabelFilterChange={setIssueLabelFilter}
            onIssueAssigneeFilterChange={setIssueAssigneeFilter}
            onIssueViewModeChange={handleIssueViewModeChange}
            onClearIssueFilters={clearIssueFilters}
            onOpenProjectMembers={openProjectMembersDialog}
            onOpenProjectWorkflow={openProjectWorkflowDialog}
            onOpenCreateIssue={openCreateIssueDialog}
            onIssueSelect={handleIssueSelect}
            onReorderIssue={handleReorderIssue}
            onQuickCreateIssue={handleQuickCreateIssue}
            onResetQuickCreateIssue={() => quickCreateIssueMutation.reset()}
          />
          </Suspense>
        )}
      </section>

      <CreateProjectDialog
        isOpen={activeCreateDialog === 'project'}
        onSubmit={handleCreateProject}
        onClose={closeCreateDialog}
        canCreateProject={canLoadCurrentWorkspace && currentWorkspace?.role !== 'GUEST'}
        isSubmitting={createProjectMutation.isPending}
        error={createProjectMutation.error}
      />
      <CreateWorkspaceDialog
        isOpen={isCreateWorkspaceDialogOpen}
        onSubmit={(values) => createWorkspaceMutation.mutate(values)}
        onClose={closeCreateWorkspaceDialog}
        isSubmitting={createWorkspaceMutation.isPending}
        error={createWorkspaceMutation.error}
      />
      {isWorkspaceInvitationsDialogOpen ? (
      <Suspense fallback={null}>
      <WorkspaceInvitationsDialog
        isOpen
        currentWorkspace={currentWorkspace}
        invitations={workspaceInvitations}
        latestInvitationLink={latestInvitationLink}
        isLoading={workspaceInvitationsQuery.isLoading}
        hasMore={workspaceInvitationsQuery.hasNextPage}
        isLoadingMore={workspaceInvitationsQuery.isFetchingNextPage}
        onLoadMore={() => void workspaceInvitationsQuery.fetchNextPage()}
        isMutating={
          createWorkspaceInvitationMutation.isPending ||
          reissueWorkspaceInvitationMutation.isPending ||
          revokeWorkspaceInvitationMutation.isPending
        }
        error={
          workspaceInvitationsQuery.error ??
          createWorkspaceInvitationMutation.error ??
          reissueWorkspaceInvitationMutation.error ??
          revokeWorkspaceInvitationMutation.error
        }
        onSubmit={async (values) => {
          await createWorkspaceInvitationMutation.mutateAsync(values)
        }}
        onReissue={(invitationId) => reissueWorkspaceInvitationMutation.mutate(invitationId)}
        onRevoke={(invitationId) => revokeWorkspaceInvitationMutation.mutate(invitationId)}
        onClose={closeWorkspaceInvitationsDialog}
      />
      </Suspense>
      ) : null}
      {activeCreateDialog === 'issue' ? (
      <Suspense fallback={null}>
      <CreateIssueDialog
        isOpen
        selectedProject={selectedProject}
        projectWorkflowStates={projectWorkflowStates}
        initialWorkflowStateId={createIssueDefaultWorkflowStateId}
        initialTitle={createIssueDefaultTitle}
        initialAssigneeUserId={createIssueDefaultAssigneeUserId}
        projectLabels={projectLabels}
        projectMembers={activeProjectMembers}
        onCreateLabel={handleCreateIssueLabel}
        onSubmit={handleCreateIssue}
        onClose={closeCreateDialog}
        isSubmitting={createIssueMutation.isPending}
        isCreatingLabel={createProjectLabelMutation.isPending}
        error={createIssueMutation.error}
        createLabelError={createProjectLabelMutation.error}
      />
      </Suspense>
      ) : null}
      {isProjectMembersDialogOpen ? (
      <Suspense fallback={null}>
      <ProjectMembersDialog
        isOpen
        selectedProject={selectedProject}
        projectMembers={projectMembers}
        workspaceMembers={workspaceMembers}
        addableWorkspaceMembers={addableWorkspaceMembers}
        onSubmit={handleAddProjectMember}
        onUpdateRole={handleUpdateProjectMemberRole}
        onRemove={handleRemoveProjectMember}
        onClose={closeProjectMembersDialog}
        canManageMembers={canManageProjectMembers}
        isLoadingProjectMembers={projectMembersQuery.isLoading}
        isLoadingWorkspaceMembers={workspaceMembersQuery.isLoading}
        projectMembersError={projectMembersQuery.error}
        workspaceMembersError={workspaceMembersQuery.error}
        isSubmitting={addProjectMemberMutation.isPending}
        isMutating={
          addProjectMemberMutation.isPending ||
          updateProjectMemberMutation.isPending ||
          removeProjectMemberMutation.isPending
        }
        updatingMemberId={
          updateProjectMemberMutation.isPending
            ? (updateProjectMemberMutation.variables?.memberId ?? null)
            : null
        }
        removingMemberId={
          removeProjectMemberMutation.isPending
            ? (removeProjectMemberMutation.variables?.memberId ?? null)
            : null
        }
        addError={addProjectMemberMutation.error}
        updateError={updateProjectMemberMutation.error}
        removeError={removeProjectMemberMutation.error}
      />
      </Suspense>
      ) : null}
      {isProjectWorkflowDialogOpen ? (
      <Suspense fallback={null}>
      <ProjectWorkflowDialog
        isOpen
        selectedProject={selectedProject}
        workflowStates={projectWorkflowStates}
        onSubmit={handleCreateProjectWorkflowState}
        onUpdate={handleUpdateProjectWorkflowState}
        onReorder={handleReorderProjectWorkflowState}
        onClose={closeProjectWorkflowDialog}
        canCreateWorkflowStates={canManageProjectMembers}
        isLoadingWorkflowStates={projectWorkflowStatesQuery.isLoading}
        workflowStatesError={projectWorkflowStatesQuery.error}
        isSubmitting={createProjectWorkflowStateMutation.isPending}
        isUpdating={updateProjectWorkflowStateMutation.isPending}
        isReordering={reorderProjectWorkflowStatesMutation.isPending}
        error={
          createProjectWorkflowStateMutation.error ??
          updateProjectWorkflowStateMutation.error ??
          reorderProjectWorkflowStatesMutation.error
        }
      />
      </Suspense>
      ) : null}
    </main>
  )
}
