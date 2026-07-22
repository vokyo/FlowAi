import { lazy, Suspense, useMemo } from 'react'
import {
  CalendarDays,
  Circle,
  CircleDot,
  FolderKanban,
  LayoutList,
  Loader2,
  MessageSquare,
  Plus,
  Search,
  UserCircle,
  Users,
} from 'lucide-react'
import type { AuthUser, AuthWorkspace } from '@/auth/auth-api'
import type { CursorPage } from '@/api/pagination'
import { Button } from '@/components/ui/button'
import type {
  IssuePriority,
  IssueSummary,
  Project,
  ProjectBoard,
  ProjectLabel,
  ProjectMember,
  ProjectWorkflowState,
} from '@/work/work-api'
import {
  filterProjectBoard,
  type BoardIssueView,
  type IssueGroup,
  type IssueWorkflowFilter,
} from '@/work/board-utils'
import {
  ISSUE_PRIORITIES,
  PRIORITY_LABELS,
  type CreateIssueDialogSeed,
  type KanbanReorder,
  type QuickCreateIssueMutationVariables,
} from '@/features/project-shell/project-model'
import type { IssueViewMode } from '@/features/project-shell/route-utils'
import { formatDate, formatDateOnly, statusForIcon } from '@/features/project-shell/display-utils'
import {
  BreadcrumbLine,
  EmptyState,
  ErrorState,
  InlineState,
  LabelBadge,
  PriorityBadge,
  StatusIcon,
} from '@/features/project-shell/feature-ui'
import { ProjectEmptyState } from './ProjectEmptyState'

const BoardFeature = lazy(() =>
  import('@/features/board/BoardFeature').then((module) => ({ default: module.BoardFeature })),
)

export function IssueListFeature({
  currentWorkspace,
  currentUser,
  selectedProject,
  projectMembers,
  projectLabels,
  projectWorkflowStates,
  projectBoard,
  issues,
  issueGroups,
  issueViewMode,
  boardIssueView,
  selectedIssueId,
  isLoadingIssues,
  issuesError,
  hasMoreIssues,
  isLoadingMoreIssues,
  onLoadMoreIssues,
  isLoadingProjectBoard,
  projectBoardError,
  onBoardColumnPageLoaded,
  reorderIssueError,
  isReorderingIssue,
  quickCreateIssueError,
  isQuickCreatingIssue,
  canUseQuickCreateShortcut,
  isLoadingProjectMembers,
  isLoadingProjects,
  hasProjects,
  canCreateProject,
  issueSearchQuery,
  issueWorkflowFilter,
  issuePriorityFilter,
  issueLabelFilter,
  issueAssigneeFilter,
  hasIssueFilters,
  onIssueSearchQueryChange,
  onIssueWorkflowFilterChange,
  onIssuePriorityFilterChange,
  onIssueLabelFilterChange,
  onIssueAssigneeFilterChange,
  onIssueViewModeChange,
  onClearIssueFilters,
  onOpenProjectMembers,
  onOpenProjectWorkflow,
  onOpenCreateProject,
  onOpenCreateIssue,
  onIssueSelect,
  onReorderIssue,
  onQuickCreateIssue,
  onResetQuickCreateIssue,
}: {
  currentWorkspace: AuthWorkspace | null
  currentUser: AuthUser | null
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  projectWorkflowStates: ProjectWorkflowState[]
  projectBoard: ProjectBoard | null
  issues: IssueSummary[]
  issueGroups: IssueGroup[]
  issueViewMode: IssueViewMode
  boardIssueView: BoardIssueView
  selectedIssueId: string | null
  isLoadingIssues: boolean
  issuesError: Error | null
  hasMoreIssues: boolean
  isLoadingMoreIssues: boolean
  onLoadMoreIssues: () => void
  isLoadingProjectBoard: boolean
  projectBoardError: Error | null
  onBoardColumnPageLoaded: (
    workflowStateId: string,
    page: CursorPage<IssueSummary>,
  ) => void
  reorderIssueError: Error | null
  isReorderingIssue: boolean
  quickCreateIssueError: Error | null
  isQuickCreatingIssue: boolean
  canUseQuickCreateShortcut: boolean
  isLoadingProjectMembers: boolean
  isLoadingProjects: boolean
  hasProjects: boolean
  canCreateProject: boolean
  issueSearchQuery: string
  issueWorkflowFilter: IssueWorkflowFilter
  issuePriorityFilter: IssuePriority | ''
  issueLabelFilter: string
  issueAssigneeFilter: string
  hasIssueFilters: boolean
  onIssueSearchQueryChange: (query: string) => void
  onIssueWorkflowFilterChange: (status: IssueWorkflowFilter) => void
  onIssuePriorityFilterChange: (priority: IssuePriority | '') => void
  onIssueLabelFilterChange: (labelId: string) => void
  onIssueAssigneeFilterChange: (assigneeUserId: string) => void
  onIssueViewModeChange: (viewMode: IssueViewMode) => void
  onClearIssueFilters: () => void
  onOpenProjectMembers: () => void
  onOpenProjectWorkflow: () => void
  onOpenCreateProject: () => void
  onOpenCreateIssue: (
    workflowState?: ProjectWorkflowState | null,
    seed?: CreateIssueDialogSeed,
  ) => void
  onIssueSelect: (issueId: string) => void
  onReorderIssue: (reorder: KanbanReorder) => Promise<void>
  onQuickCreateIssue: (
    variables: Omit<QuickCreateIssueMutationVariables, 'projectId'>,
  ) => Promise<IssueSummary | null>
  onResetQuickCreateIssue: () => void
}) {
  const shouldShowEmptyIssues =
    issueViewMode === 'LIST' &&
    selectedProject &&
    !isLoadingIssues &&
    !issuesError &&
    issues.length === 0
  const visibleProjectBoard = useMemo(
    () => filterProjectBoard(projectBoard, boardIssueView, currentUser?.id ?? null),
    [boardIssueView, currentUser?.id, projectBoard],
  )
  const boardIssueCount =
    visibleProjectBoard?.columns.reduce(
      (count, column) => count + column.issues.length,
      0,
    ) ?? 0
  const visibleIssueCount = issueViewMode === 'BOARD' ? boardIssueCount : issues.length

  return (
    <div className="content-page">
      <header className="content-header">
        <div>
          <BreadcrumbLine
            items={[currentWorkspace?.name ?? 'Workspace', selectedProject?.name ?? 'Issues']}
          />
          <h1>{selectedProject?.name ?? (hasProjects ? 'Choose a project' : 'Projects')}</h1>
          {selectedProject?.description ? <p>{selectedProject.description}</p> : null}
        </div>
        <div className="content-header-actions">
          {selectedProject ? (
            <div className="issue-view-switch" role="group" aria-label="Issue view">
              <Button
                type="button"
                variant={issueViewMode === 'BOARD' ? 'secondary' : 'ghost'}
                size="sm"
                aria-pressed={issueViewMode === 'BOARD'}
                onClick={() => onIssueViewModeChange('BOARD')}
              >
                <FolderKanban aria-hidden="true" />
                Board
              </Button>
              <Button
                type="button"
                variant={issueViewMode === 'LIST' ? 'secondary' : 'ghost'}
                size="sm"
                aria-pressed={issueViewMode === 'LIST'}
                onClick={() => onIssueViewModeChange('LIST')}
              >
                <LayoutList aria-hidden="true" />
                List
              </Button>
            </div>
          ) : null}
          {selectedProject ? (
            <span className="app-pill">
              {issueViewMode === 'BOARD' ? (
                <FolderKanban aria-hidden="true" />
              ) : (
                <LayoutList aria-hidden="true" />
              )}
              {visibleIssueCount} issues
            </span>
          ) : null}
          {selectedProject ? (
            <Button
              type="button"
              variant="outline"
              onClick={onOpenProjectWorkflow}
              aria-label="Open workflow states"
            >
              <CircleDot aria-hidden="true" />
              {projectWorkflowStates.length} statuses
            </Button>
          ) : null}
          {selectedProject ? (
            <Button
              type="button"
              variant="outline"
              onClick={onOpenProjectMembers}
              disabled={isLoadingProjectMembers}
              aria-label="Open project members"
            >
              <Users aria-hidden="true" />
              {isLoadingProjectMembers ? 'Members' : `${projectMembers.length} members`}
            </Button>
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
        <ProjectEmptyState
          hasProjects={hasProjects}
          canCreateProject={canCreateProject}
          onCreateProject={onOpenCreateProject}
        />
      ) : null}
      {issueViewMode === 'LIST' && isLoadingIssues ? (
        <InlineState>Loading issues.</InlineState>
      ) : null}
      {issueViewMode === 'LIST' && issuesError ? <ErrorState error={issuesError} /> : null}
      {issueViewMode === 'BOARD' && isLoadingProjectBoard && !projectBoard ? (
        <BoardLoadingState workflowStates={projectWorkflowStates} />
      ) : null}
      {issueViewMode === 'BOARD' && projectBoardError ? (
        <ErrorState error={projectBoardError} />
      ) : null}

      {selectedProject && issueViewMode === 'LIST' ? (
        <div className="issue-filter-bar" aria-label="Issue filters">
          <label className="issue-search-field">
            <Search aria-hidden="true" />
            <input
              type="search"
              placeholder="Search issues"
              value={issueSearchQuery}
              onChange={(event) => onIssueSearchQueryChange(event.target.value)}
            />
          </label>
          <label className="app-field">
            Status
            <select
              value={issueWorkflowFilter}
              onChange={(event) => onIssueWorkflowFilterChange(event.target.value as IssueWorkflowFilter)}
            >
              <option value="ACTIVE">Active</option>
              {projectWorkflowStates.map((workflowState) => (
                <option key={workflowState.id} value={workflowState.id}>
                  {workflowState.name}
                </option>
              ))}
              <option value="ARCHIVED">Archived</option>
            </select>
          </label>
          <label className="app-field">
            Priority
            <select
              value={issuePriorityFilter}
              onChange={(event) => onIssuePriorityFilterChange(event.target.value as IssuePriority | '')}
            >
              <option value="">Any priority</option>
              {ISSUE_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {PRIORITY_LABELS[priority]}
                </option>
              ))}
            </select>
          </label>
          <label className="app-field">
            Label
            <select
              value={issueLabelFilter}
              onChange={(event) => onIssueLabelFilterChange(event.target.value)}
              disabled={projectLabels.length === 0}
            >
              <option value="">Any label</option>
              {projectLabels.map((label) => (
                <option key={label.id} value={label.id}>
                  {label.name}
                </option>
              ))}
            </select>
          </label>
          <label className="app-field">
            Assignee
            <select
              value={issueAssigneeFilter}
              onChange={(event) => onIssueAssigneeFilterChange(event.target.value)}
              disabled={projectMembers.length === 0}
            >
              <option value="">Any assignee</option>
              {projectMembers.map((member) => (
                <option key={member.id} value={member.userId}>
                  {member.displayName || member.email}
                </option>
              ))}
            </select>
          </label>
          <Button
            type="button"
            variant="ghost"
            onClick={onClearIssueFilters}
            disabled={!hasIssueFilters}
          >
            Clear
          </Button>
        </div>
      ) : null}

      {shouldShowEmptyIssues ? (
        <EmptyState
          title={hasIssueFilters ? 'No matching issues' : 'No active issues yet'}
          body={
            hasIssueFilters
              ? 'Adjust the search or filters to find issues in this project.'
              : 'Create an issue to start tracking work in this project.'
          }
        />
      ) : null}

      {selectedProject &&
      issueViewMode === 'BOARD' &&
      visibleProjectBoard ? (
        <Suspense fallback={<BoardLoadingState workflowStates={projectWorkflowStates} />}>
          <BoardFeature
            board={visibleProjectBoard}
            completeBoard={projectBoard ?? visibleProjectBoard}
            workspaceId={currentWorkspace?.id ?? ''}
            boardIssueView={boardIssueView}
            currentUserId={currentUser?.id ?? null}
            selectedIssueId={selectedIssueId}
            isReordering={isReorderingIssue}
            isQuickCreating={isQuickCreatingIssue}
            canUseQuickCreateShortcut={canUseQuickCreateShortcut}
            reorderError={reorderIssueError}
            quickCreateError={quickCreateIssueError}
            key={`${visibleProjectBoard.projectId}-${boardIssueView}`}
            onIssueSelect={onIssueSelect}
            onOpenFullCreate={onOpenCreateIssue}
            onReorder={onReorderIssue}
            onQuickCreate={onQuickCreateIssue}
            onResetQuickCreate={onResetQuickCreateIssue}
            onBoardColumnPageLoaded={onBoardColumnPageLoaded}
          />
        </Suspense>
      ) : null}

      {selectedProject && issueViewMode === 'LIST' && !issuesError && issues.length > 0 ? (
        <div className="status-list" aria-label="Issues grouped by status">
          {issueGroups.map((group) => (
            <IssueStatusSection
              group={group}
              key={group.workflowState?.id ?? group.status}
              selectedIssueId={selectedIssueId}
              onIssueSelect={onIssueSelect}
              onOpenCreateIssue={onOpenCreateIssue}
            />
          ))}
        </div>
      ) : null}

      {selectedProject && issueViewMode === 'LIST' && hasMoreIssues ? (
        <div className="pagination-actions">
          <Button
            type="button"
            variant="outline"
            disabled={isLoadingMoreIssues}
            onClick={onLoadMoreIssues}
          >
            {isLoadingMoreIssues ? <Loader2 className="auth-spin" aria-hidden="true" /> : null}
            {isLoadingMoreIssues ? 'Loading issues' : 'Load more issues'}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function BoardLoadingState({
  workflowStates,
}: {
  workflowStates: ProjectWorkflowState[]
}) {
  const loadingColumns =
    workflowStates.length > 0 ? workflowStates : Array<ProjectWorkflowState | null>(3).fill(null)

  return (
    <section className="kanban-board-region" aria-label="Loading project board" role="status">
      <p className="kanban-save-state">
        <Loader2 className="auth-spin" aria-hidden="true" />
        Loading board
      </p>
      <div className="kanban-board-scroll">
        <div className="kanban-board" aria-hidden="true">
          {loadingColumns.map((workflowState, index) => (
            <section className="kanban-column kanban-column-loading" key={workflowState?.id ?? index}>
              <header className="kanban-column-header">
                <span className="kanban-column-title">
                  <Circle aria-hidden="true" className="status-icon" />
                  <strong>{workflowState?.name ?? 'Status'}</strong>
                </span>
              </header>
              <div className="kanban-loading-block" />
            </section>
          ))}
        </div>
      </div>
    </section>
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
  onOpenCreateIssue: (workflowState?: ProjectWorkflowState | null) => void
}) {
  const canCreateIssueInStatus = group.status !== 'ARCHIVED'

  return (
    <section className="status-section">
      <div className="status-section-header">
        <span>
          <StatusIcon status={group.status} />
          {group.label}
        </span>
        <span className="status-section-actions">
          <small>{group.issues.length}</small>
          {canCreateIssueInStatus ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={() => onOpenCreateIssue(group.workflowState)}
              aria-label={`Create issue in ${group.label}`}
              title="Create issue"
            >
              <Plus aria-hidden="true" />
            </Button>
          ) : null}
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
        <StatusIcon status={statusForIcon(issue)} />
      </span>
      <span className="issue-row-main">
        <strong>{issue.title}</strong>
        {issue.description ? <small>{issue.description}</small> : null}
        {issue.labels.length > 0 ? (
          <span className="issue-label-row">
            {issue.labels.map((label) => (
              <LabelBadge label={label} key={label.id} />
            ))}
          </span>
        ) : null}
      </span>
      <span className="issue-row-meta">
        <span className="issue-comment-count">
          <CircleDot aria-hidden="true" />
          {issue.status === 'ARCHIVED' ? 'Archived' : issue.workflowState.name}
        </span>
        <PriorityBadge priority={issue.priority} />
        <span className="issue-comment-count">
          <UserCircle aria-hidden="true" />
          {issue.assignee ? issue.assignee.displayName || issue.assignee.email : 'Unassigned'}
        </span>
        {issue.dueDate ? (
          <span className="issue-comment-count">
            <CalendarDays aria-hidden="true" />
            {formatDateOnly(issue.dueDate)}
          </span>
        ) : null}
        <span className="issue-comment-count">
          <MessageSquare aria-hidden="true" />
          {issue.commentCount ?? 0}
        </span>
        <span>{formatDate(issue.updatedAt)}</span>
      </span>
    </button>
  )
}

