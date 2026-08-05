import { useNavigate } from 'react-router'
import {
  ChartColumn,
  Check,
  ChevronDown,
  ChevronRight,
  Circle,
  FolderKanban,
  LayoutList,
  Loader2,
  LogOut,
  Mail,
  PanelRight,
  Plus,
  Settings,
  UserCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { InlineState } from '@/ui/feature-ui'
import type { AuthUser, AuthWorkspace } from '@/api/auth-api'
import type { Project } from '@/api/work-api'
import type { BoardIssueView } from '@/domain/board-utils'

type IssueViewMode = 'BOARD' | 'LIST'

export function WorkspaceSidebar({
  currentUser,
  currentWorkspace,
  workspaces,
  isLoadingWorkspace,
  isLoadingWorkspaces,
  workspacesError,
  isWorkspaceMenuOpen,
  isSwitchingWorkspace,
  onToggleWorkspaceMenu,
  onCloseWorkspaceMenu,
  onWorkspaceSelect,
  onOpenCreateWorkspace,
  onOpenWorkspaceInvitations,
  canManageWorkspaceInvitations,
  projects,
  selectedProjectId,
  isAnalyticsRoute,
  issueViewMode,
  boardIssueView,
  isLoadingProjects,
  projectsError,
  areProjectsOpen,
  onToggleProjects,
  onOpenCreateProject,
  canCreateProject,
  canSelectViews,
  onViewSelect,
  onAnalyticsSelect,
  onProjectSelect,
  onSignOut,
  isMobileOpen,
  onMobileClose,
}: {
  currentUser: AuthUser | null
  currentWorkspace: AuthWorkspace | null
  workspaces: AuthWorkspace[]
  isLoadingWorkspace: boolean
  isLoadingWorkspaces: boolean
  workspacesError: Error | null
  isWorkspaceMenuOpen: boolean
  isSwitchingWorkspace: boolean
  onToggleWorkspaceMenu: () => void
  onCloseWorkspaceMenu: () => void
  onWorkspaceSelect: (workspaceId: string) => void
  onOpenCreateWorkspace: () => void
  onOpenWorkspaceInvitations: () => void
  canManageWorkspaceInvitations: boolean
  projects: Project[]
  selectedProjectId: string | null
  isAnalyticsRoute: boolean
  issueViewMode: IssueViewMode
  boardIssueView: BoardIssueView
  isLoadingProjects: boolean
  projectsError: Error | null
  areProjectsOpen: boolean
  onToggleProjects: () => void
  onOpenCreateProject: () => void
  canCreateProject: boolean
  canSelectViews: boolean
  onViewSelect: (view: BoardIssueView) => void
  onAnalyticsSelect: () => void
  onProjectSelect: (projectId: string) => void
  onSignOut: () => void
  isMobileOpen: boolean
  onMobileClose: () => void
}) {
  const navigate = useNavigate()
  return (
    <aside className="app-sidebar" data-mobile-open={isMobileOpen}>
      <div className="sidebar-brand-row">
        <div className="sidebar-brand" aria-label="FlowAI">
          <img src="/favicon.svg" alt="" aria-hidden="true" />
          <strong>FlowAI</strong>
        </div>
        <UserMenu
          currentUser={currentUser}
          onOpenSettings={() => {
            onMobileClose()
            navigate('/app/settings')
          }}
          onSignOut={() => {
            onMobileClose()
            onSignOut()
          }}
        />
      </div>
      <div className="sidebar-topbar">
        <WorkspaceSwitcher
          currentWorkspace={currentWorkspace}
          workspaces={workspaces}
          isLoadingWorkspace={isLoadingWorkspace}
          isLoadingWorkspaces={isLoadingWorkspaces}
          error={workspacesError}
          isOpen={isWorkspaceMenuOpen}
          isSwitching={isSwitchingWorkspace}
          onToggle={onToggleWorkspaceMenu}
          onClose={onCloseWorkspaceMenu}
          onSelect={(workspaceId) => {
            onMobileClose()
            onWorkspaceSelect(workspaceId)
          }}
          onCreate={() => {
            onMobileClose()
            onOpenCreateWorkspace()
          }}
          onManageInvitations={() => {
            onMobileClose()
            onOpenWorkspaceInvitations()
          }}
          canManageInvitations={canManageWorkspaceInvitations}
        />
      </div>

      <nav className="sidebar-section" aria-label="Views">
        <div className="sidebar-section-header">
          <span>
            <PanelRight aria-hidden="true" />
            Views
          </span>
        </div>
        <div className="sidebar-list sidebar-view-list">
          <Button
            variant="ghost"
            className="sidebar-list-item sidebar-view-item"
            data-active={
              !isAnalyticsRoute && issueViewMode === 'BOARD' && boardIssueView === 'ALL'
            }
            type="button"
            disabled={!canSelectViews}
            onClick={() => {
              onMobileClose()
              onViewSelect('ALL')
            }}
            aria-current={
              !isAnalyticsRoute && issueViewMode === 'BOARD' && boardIssueView === 'ALL'
                ? 'page'
                : undefined
            }
          >
            <LayoutList aria-hidden="true" />
            <span>
              <strong>All issues</strong>
            </span>
          </Button>
          <Button
            variant="ghost"
            className="sidebar-list-item sidebar-view-item"
            data-active={
              !isAnalyticsRoute && issueViewMode === 'BOARD' && boardIssueView === 'MINE'
            }
            type="button"
            disabled={!canSelectViews}
            onClick={() => {
              onMobileClose()
              onViewSelect('MINE')
            }}
            aria-current={
              !isAnalyticsRoute && issueViewMode === 'BOARD' && boardIssueView === 'MINE'
                ? 'page'
                : undefined
            }
          >
            <UserCircle aria-hidden="true" />
            <span>
              <strong>My issues</strong>
            </span>
          </Button>
          <Button
            variant="ghost"
            className="sidebar-list-item sidebar-view-item"
            data-active={
              !isAnalyticsRoute &&
              issueViewMode === 'BOARD' &&
              boardIssueView === 'UNASSIGNED'
            }
            type="button"
            disabled={!canSelectViews}
            onClick={() => {
              onMobileClose()
              onViewSelect('UNASSIGNED')
            }}
            aria-current={
              !isAnalyticsRoute &&
              issueViewMode === 'BOARD' &&
              boardIssueView === 'UNASSIGNED'
                ? 'page'
                : undefined
            }
          >
            <Circle aria-hidden="true" />
            <span>
              <strong>Unassigned</strong>
            </span>
          </Button>
          <Button
            variant="ghost"
            className="sidebar-list-item sidebar-view-item"
            data-active={isAnalyticsRoute}
            type="button"
            disabled={!canSelectViews}
            onClick={() => {
              onMobileClose()
              onAnalyticsSelect()
            }}
            aria-current={isAnalyticsRoute ? 'page' : undefined}
          >
            <ChartColumn aria-hidden="true" />
            <span>
              <strong>Analytics</strong>
            </span>
          </Button>
        </div>
      </nav>

      <nav className="sidebar-section" aria-label="Projects">
        <div className="sidebar-section-header sidebar-section-header-interactive">
          <Button
            variant="ghost"
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
          </Button>
          <span className="sidebar-section-actions">
            <small>{projects.length}</small>
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={() => {
                onMobileClose()
                onOpenCreateProject()
              }}
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
            onProjectSelect={(projectId) => {
              onMobileClose()
              onProjectSelect(projectId)
            }}
            isLoading={isLoadingProjects}
          />
        ) : null}
      </nav>
    </aside>
  )
}

function UserMenu({
  currentUser,
  onOpenSettings,
  onSignOut,
}: {
  currentUser: AuthUser | null
  onOpenSettings: () => void
  onSignOut: () => void
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="sidebar-user-trigger" type="button" aria-label="Open user menu">
          {getInitials(currentUser?.displayName || currentUser?.email || 'User')}
        </button>
      </DropdownMenuTrigger>
      {/* w-56 overrides the default trigger-width sizing — the trigger here is a
          30px avatar, which would collapse the menu. */}
      <DropdownMenuContent align="end" className="w-56" aria-label="User menu">
        <div className="sidebar-user-summary">
          <strong>{currentUser?.displayName || 'FlowAI user'}</strong>
          {currentUser?.email ? <small>{currentUser.email}</small> : null}
        </div>
        <DropdownMenuItem onSelect={onOpenSettings}>
          <Settings aria-hidden="true" />
          Settings
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={onSignOut}>
          <LogOut aria-hidden="true" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function WorkspaceSwitcher({
  currentWorkspace,
  workspaces,
  isLoadingWorkspace,
  isLoadingWorkspaces,
  error,
  isOpen,
  isSwitching,
  onToggle,
  onClose,
  onSelect,
  onCreate,
  onManageInvitations,
  canManageInvitations,
}: {
  currentWorkspace: AuthWorkspace | null
  workspaces: AuthWorkspace[]
  isLoadingWorkspace: boolean
  isLoadingWorkspaces: boolean
  error: Error | null
  isOpen: boolean
  isSwitching: boolean
  onToggle: () => void
  onClose: () => void
  onSelect: (workspaceId: string) => void
  onCreate: () => void
  onManageInvitations: () => void
  canManageInvitations: boolean
}) {
  return (
    <DropdownMenu open={isOpen} onOpenChange={(open) => (open ? onToggle() : onClose())}>
      <DropdownMenuTrigger asChild>
        <button
          className="workspace-switcher"
          type="button"
          disabled={isLoadingWorkspace || isSwitching}
        >
          <span className="workspace-avatar" aria-hidden="true">
            {isSwitching ? <Loader2 className="auth-spin" /> : getInitials(currentWorkspace?.name ?? 'FlowAI')}
          </span>
          <span className="workspace-select-label">
            Workspace
            <strong>{isLoadingWorkspace ? 'Loading workspace' : currentWorkspace?.name ?? 'Workspace'}</strong>
          </span>
          <ChevronDown aria-hidden="true" className="workspace-switcher-chevron" />
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="w-72" aria-label="Workspaces">
        <DropdownMenuLabel className="workspace-menu-heading">Your workspaces</DropdownMenuLabel>
        {isLoadingWorkspaces ? <small>Loading workspaces...</small> : null}
        {error ? <small className="workspace-menu-error">{error.message}</small> : null}
        {workspaces.map((workspace) => (
          <DropdownMenuItem
            key={workspace.id}
            className="workspace-menu-item"
            data-active={workspace.id === currentWorkspace?.id}
            disabled={isSwitching}
            onSelect={() => onSelect(workspace.id)}
          >
            <span className="workspace-avatar workspace-avatar-small" aria-hidden="true">
              {getInitials(workspace.name)}
            </span>
            <span>
              <strong>{workspace.name}</strong>
              <small>{titleCaseWorkspaceRole(workspace.role)}</small>
            </span>
            {workspace.id === currentWorkspace?.id ? <Check aria-hidden="true" /> : null}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onCreate}>
          <Plus aria-hidden="true" />
          Create workspace
        </DropdownMenuItem>
        {canManageInvitations ? (
          <DropdownMenuItem onSelect={onManageInvitations}>
            <Mail aria-hidden="true" />
            Manage invitations
          </DropdownMenuItem>
        ) : null}
      </DropdownMenuContent>
    </DropdownMenu>
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
  // An empty section renders as nothing. The header already carries the count
  // and the "+" button, so a placeholder line only adds noise to the state every
  // new workspace starts in.
  if (!isLoading && projects.length === 0) {
    return null
  }

  return (
    <div className="sidebar-list">
      {projects.map((project) => (
        <Button
          variant="ghost"
          className="sidebar-list-item"
          data-active={project.id === selectedProjectId}
          key={project.id}
          type="button"
          onClick={() => onProjectSelect(project.id)}
        >
          <FolderKanban aria-hidden="true" />
          {/* Name only, like the view items above. The row wraps rather than
              truncates, so a description here sets the sidebar's width against
              whatever the longest one happens to be. */}
          <span>
            <strong>{project.name}</strong>
          </span>
        </Button>
      ))}
    </div>
  )
}

function ErrorState({ error }: { error: Error }) {
  return <p className="app-error">{error.message}</p>
}

function getInitials(value: string) {
  const words = value.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return 'F'
  return words.slice(0, 2).map((word) => word[0]?.toUpperCase()).join('')
}

function titleCaseWorkspaceRole(role: string) {
  return role.charAt(0) + role.slice(1).toLowerCase()
}
