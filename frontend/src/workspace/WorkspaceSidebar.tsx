import { useEffect, useRef, useState } from 'react'
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
import type { AuthUser, AuthWorkspace } from '@/auth/auth-api'
import type { Project } from '@/work/work-api'
import type { BoardIssueView } from '@/work/board-utils'

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
          <button
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
          </button>
          <button
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
          </button>
          <button
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
          </button>
          <button
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
          </button>
        </div>
      </nav>

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
  const [isOpen, setIsOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!isOpen) return

    function closeWhenOutside(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setIsOpen(false)
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setIsOpen(false)
      }
    }

    document.addEventListener('pointerdown', closeWhenOutside)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('pointerdown', closeWhenOutside)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [isOpen])

  return (
    <div className="sidebar-user-menu-root" ref={rootRef}>
      <button
        className="sidebar-user-trigger"
        type="button"
        aria-label="Open user menu"
        aria-expanded={isOpen}
        aria-haspopup="menu"
        onClick={() => setIsOpen((open) => !open)}
      >
        {getInitials(currentUser?.displayName || currentUser?.email || 'User')}
      </button>
      {isOpen ? (
        <div className="sidebar-user-menu" role="menu" aria-label="User menu">
          <div className="sidebar-user-summary">
            <strong>{currentUser?.displayName || 'FlowAI user'}</strong>
            {currentUser?.email ? <small>{currentUser.email}</small> : null}
          </div>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setIsOpen(false)
              onOpenSettings()
            }}
          >
            <Settings aria-hidden="true" />
            Settings
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setIsOpen(false)
              onSignOut()
            }}
          >
            <LogOut aria-hidden="true" />
            Sign out
          </button>
        </div>
      ) : null}
    </div>
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
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!isOpen) return

    function handlePointerDown(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        onClose()
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose()
      }
    }

    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [isOpen, onClose])

  return (
    <div className="workspace-switcher-root" ref={rootRef}>
      <button
        className="workspace-switcher"
        type="button"
        onClick={onToggle}
        aria-expanded={isOpen}
        aria-haspopup="menu"
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

      {isOpen ? (
        <div className="workspace-menu" role="menu" aria-label="Workspaces">
          <div className="workspace-menu-heading">Your workspaces</div>
          {isLoadingWorkspaces ? <small>Loading workspaces...</small> : null}
          {error ? <small className="workspace-menu-error">{error.message}</small> : null}
          <div className="workspace-menu-list">
            {workspaces.map((workspace) => (
              <button
                key={workspace.id}
                className="workspace-menu-item"
                type="button"
                role="menuitem"
                data-active={workspace.id === currentWorkspace?.id}
                onClick={() => onSelect(workspace.id)}
                disabled={isSwitching}
              >
                <span className="workspace-avatar workspace-avatar-small" aria-hidden="true">
                  {getInitials(workspace.name)}
                </span>
                <span>
                  <strong>{workspace.name}</strong>
                  <small>{titleCaseWorkspaceRole(workspace.role)}</small>
                </span>
                {workspace.id === currentWorkspace?.id ? <Check aria-hidden="true" /> : null}
              </button>
            ))}
          </div>
          <div className="workspace-menu-actions">
            <button type="button" role="menuitem" onClick={onCreate}>
              <Plus aria-hidden="true" />
              Create workspace
            </button>
            {canManageInvitations ? (
              <button type="button" role="menuitem" onClick={onManageInvitations}>
                <Mail aria-hidden="true" />
                Manage invitations
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
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

function InlineState({ children }: { children: React.ReactNode }) {
  return <p className="inline-state">{children}</p>
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
