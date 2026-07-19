import { lazy, Suspense, useEffect, useState } from 'react'
import { Menu } from 'lucide-react'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { useInvitationMutations } from '@/features/invitations/useInvitationMutations'
import { useInvitationQuery } from '@/features/invitations/useInvitationQuery'
import { AnalyticsRouteContainer } from '@/features/project-shell/AnalyticsRouteContainer'
import { IssueDetailRouteContainer } from '@/features/project-shell/IssueDetailRouteContainer'
import { ProjectRouteContainer } from '@/features/project-shell/ProjectRouteContainer'
import {
  CreateProjectDialog,
  CreateWorkspaceDialog,
  WorkspaceMismatchState,
} from '@/features/project-shell/ProjectShellStates'
import type { CreateProjectFormValues } from '@/features/project-shell/project-model'
import {
  analyticsRangeFromSearchParams,
  analyticsSearchParams,
  boardIssueViewFromSearchParams,
  isProjectAnalyticsPath,
  issueViewModeFromSearchParams,
  issueViewSearchParams,
  normalizeAppSearchParams,
  pathWithSearchParams,
  projectAnalyticsPath,
  projectPath,
  workViewSearchParams,
} from '@/features/project-shell/route-utils'
import { useProjectShellMutations } from '@/features/project-shell/useProjectShellMutations'
import { useProjectShellQueries } from '@/features/project-shell/useProjectShellQueries'
import type { BoardIssueView } from '@/work/board-utils'
import { WorkspaceSidebar } from '@/workspace/WorkspaceSidebar'

const WorkspaceInvitationsDialog = lazy(() =>
  import('@/features/invitations/WorkspaceInvitationsDialog').then((module) => ({
    default: module.WorkspaceInvitationsDialog,
  })),
)

type ProjectShellPageProps = {
  onSignOut: () => void
  onSessionChanged: () => void
}

type AppRouteParams = {
  workspaceId?: string
  projectId?: string
  issueId?: string
}

export function ProjectShellPage({ onSignOut, onSessionChanged }: ProjectShellPageProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const {
    workspaceId: routeWorkspaceId,
    projectId: routeProjectId,
    issueId: routeIssueId,
  } = useParams<AppRouteParams>()
  const [isCreateProjectDialogOpen, setIsCreateProjectDialogOpen] = useState(false)
  const [areProjectsOpen, setAreProjectsOpen] = useState(true)
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
    switchWorkspaceMutation,
    createWorkspaceMutation,
    createProjectMutation,
  } = useProjectShellMutations({
    workspaceId: currentWorkspaceId,
    workViewSearchParams: normalizedWorkViewSearchParams,
    onSessionChanged,
    onWorkspaceCreated: () => setIsCreateWorkspaceDialogOpen(false),
    onProjectCreated: () => setIsCreateProjectDialogOpen(false),
  })

  const { query: workspaceInvitationsQuery, invitations: workspaceInvitations } =
    useInvitationQuery({
      workspaceId: currentWorkspaceId,
      enabled: Boolean(
        canLoadCurrentWorkspace
        && canManageWorkspaceInvitations
        && isWorkspaceInvitationsDialogOpen
      ),
    })

  const {
    createInvitationMutation,
    reissueInvitationMutation,
    revokeInvitationMutation,
  } = useInvitationMutations({
    workspaceId: currentWorkspaceId,
    onInvitationLink: setLatestInvitationLink,
  })

  useEffect(() => {
    if (!currentWorkspaceId || !canLoadCurrentWorkspace || !projectsQuery.isSuccess) return

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
      navigate(pathWithSearchParams(firstProjectPath, normalizedSearchString), { replace: true })
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

  function handleWorkspaceSelect(workspaceId: string) {
    setIsWorkspaceMenuOpen(false)
    if (workspaceId === currentWorkspaceId) return
    switchWorkspaceMutation.reset()
    switchWorkspaceMutation.mutate(workspaceId)
  }

  function openWorkspaceInvitationsDialog() {
    setIsWorkspaceMenuOpen(false)
    setLatestInvitationLink(null)
    createInvitationMutation.reset()
    reissueInvitationMutation.reset()
    revokeInvitationMutation.reset()
    setIsWorkspaceInvitationsDialogOpen(true)
  }

  function closeWorkspaceInvitationsDialog() {
    if (
      createInvitationMutation.isPending
      || reissueInvitationMutation.isPending
      || revokeInvitationMutation.isPending
    ) return
    setIsWorkspaceInvitationsDialogOpen(false)
    setLatestInvitationLink(null)
  }

  function handleCreateProject(values: CreateProjectFormValues) {
    const name = values.name.trim()
    if (!name || !canLoadCurrentWorkspace) return
    createProjectMutation.mutate({ name, description: values.description.trim() })
  }

  function handleSidebarViewSelect(nextView: BoardIssueView) {
    if (!currentWorkspaceId || !selectedProjectId) return
    navigate(
      pathWithSearchParams(
        projectPath(currentWorkspaceId, selectedProjectId),
        workViewSearchParams(searchParams, 'BOARD', nextView),
      ),
    )
  }

  function handleProjectSelect(projectId: string) {
    if (!currentWorkspaceId) return
    const nextPath = isAnalyticsRoute
      ? projectAnalyticsPath(currentWorkspaceId, projectId)
      : projectPath(currentWorkspaceId, projectId)
    navigate(
      pathWithSearchParams(
        nextPath,
        isAnalyticsRoute ? normalizedAppSearchParams : normalizedWorkViewSearchParams,
      ),
    )
  }

  const isShellDialogOpen =
    isCreateProjectDialogOpen
    || isCreateWorkspaceDialogOpen
    || isWorkspaceInvitationsDialogOpen

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
        onOpenCreateWorkspace={() => {
          setIsWorkspaceMenuOpen(false)
          createWorkspaceMutation.reset()
          setIsCreateWorkspaceDialogOpen(true)
        }}
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
        onOpenCreateProject={() => {
          createProjectMutation.reset()
          setIsCreateProjectDialogOpen(true)
        }}
        canCreateProject={canLoadCurrentWorkspace && currentWorkspace?.role !== 'GUEST'}
        canSelectViews={Boolean(selectedProjectId)}
        onViewSelect={handleSidebarViewSelect}
        onAnalyticsSelect={() => {
          if (currentWorkspaceId && selectedProjectId) {
            navigate(
              pathWithSearchParams(
                projectAnalyticsPath(currentWorkspaceId, selectedProjectId),
                analyticsSearchParams(searchParams, analyticsRangeDays),
              ),
            )
          }
        }}
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
        ) : routeIssueId ? (
          <IssueDetailRouteContainer
            workspaceId={currentWorkspaceId}
            currentWorkspace={currentWorkspace}
            currentUser={currentUser}
            selectedProject={selectedProject}
            selectedProjectId={selectedProjectId}
            issueId={routeIssueId}
            canLoadCurrentWorkspace={canLoadCurrentWorkspace}
          />
        ) : isAnalyticsRoute ? (
          <AnalyticsRouteContainer
            workspaceId={currentWorkspaceId}
            selectedProject={selectedProject}
            selectedProjectId={selectedProjectId}
            canLoadCurrentWorkspace={canLoadCurrentWorkspace}
          />
        ) : (
          <ProjectRouteContainer
            workspaceId={currentWorkspaceId}
            currentWorkspace={currentWorkspace}
            currentUser={currentUser}
            selectedProject={selectedProject}
            selectedProjectId={selectedProjectId}
            canLoadCurrentWorkspace={canLoadCurrentWorkspace}
            isLoadingProjects={projectsQuery.isLoading}
            issueViewMode={issueViewMode}
            boardIssueView={boardIssueView}
            isShellDialogOpen={isShellDialogOpen}
          />
        )}
      </section>

      <CreateProjectDialog
        isOpen={isCreateProjectDialogOpen}
        onSubmit={handleCreateProject}
        onClose={() => {
          if (!createProjectMutation.isPending) setIsCreateProjectDialogOpen(false)
        }}
        canCreateProject={canLoadCurrentWorkspace && currentWorkspace?.role !== 'GUEST'}
        isSubmitting={createProjectMutation.isPending}
        error={createProjectMutation.error}
      />
      <CreateWorkspaceDialog
        isOpen={isCreateWorkspaceDialogOpen}
        onSubmit={(values) => createWorkspaceMutation.mutate(values)}
        onClose={() => {
          if (!createWorkspaceMutation.isPending) setIsCreateWorkspaceDialogOpen(false)
        }}
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
            isMutating={createInvitationMutation.isPending || reissueInvitationMutation.isPending || revokeInvitationMutation.isPending}
            error={workspaceInvitationsQuery.error ?? createInvitationMutation.error ?? reissueInvitationMutation.error ?? revokeInvitationMutation.error}
            onSubmit={async (values) => { await createInvitationMutation.mutateAsync(values) }}
            onReissue={(invitationId) => reissueInvitationMutation.mutate(invitationId)}
            onRevoke={(invitationId) => revokeInvitationMutation.mutate(invitationId)}
            onClose={closeWorkspaceInvitationsDialog}
          />
        </Suspense>
      ) : null}
    </main>
  )
}
