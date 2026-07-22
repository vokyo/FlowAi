import { lazy, Suspense, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import type { AuthUser, AuthWorkspace } from '@/auth/auth-api'
import { useBoardMutations } from '@/features/board/useBoardMutations'
import { useBoardQueries } from '@/features/board/useBoardQueries'
import { useIssueMutations } from '@/features/issue-list/useIssueMutations'
import { useIssueListQueries } from '@/features/issue-list/useIssueListQueries'
import { useProjectLabelsQuery } from '@/features/issue-list/useProjectLabelsQuery'
import { buildIssueListFilterState } from '@/features/issue-list/issue-filter-utils'
import { useProjectMemberMutations } from '@/features/project-members/useProjectMemberMutations'
import { useProjectMemberQueries } from '@/features/project-members/useProjectMemberQueries'
import type {
  AddProjectMemberFormValues,
  CreateIssueDialogSeed,
  CreateIssueFormValues,
  CreateProjectLabelFormValues,
  CreateProjectWorkflowStateFormValues,
  KanbanReorder,
  QuickCreateIssueMutationVariables,
  UpdateProjectWorkflowStateFormValues,
} from '@/features/project-shell/project-model'
import {
  issuePath,
  pathWithSearchParams,
  workViewSearchParams,
  type IssueViewMode,
} from '@/features/project-shell/route-utils'
import { InlineState } from '@/features/project-shell/feature-ui'
import { ISSUE_SEARCH_DEBOUNCE_MS } from '@/lib/query-config'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import type { IssuePriority, Project, ProjectMember, ProjectWorkflowState } from '@/work/work-api'
import {
  defaultWorkflowStateIdForStatus,
  groupIssuesByWorkflowState,
  type BoardIssueView,
  type IssueWorkflowFilter,
} from '@/work/board-utils'

const IssueListFeature = lazy(() =>
  import('@/features/issue-list/IssueListFeature').then((module) => ({
    default: module.IssueListFeature,
  })),
)

const CreateIssueDialog = lazy(() =>
  import('@/features/issue-list/CreateIssueDialog').then((module) => ({
    default: module.CreateIssueDialog,
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

type ProjectRouteContainerProps = {
  workspaceId: string | null
  currentWorkspace: AuthWorkspace | null
  currentUser: AuthUser | null
  selectedProject: Project | null
  selectedProjectId: string | null
  canLoadCurrentWorkspace: boolean
  isLoadingProjects: boolean
  hasProjects: boolean
  canCreateProject: boolean
  onOpenCreateProject: () => void
  issueViewMode: IssueViewMode
  boardIssueView: BoardIssueView
  isShellDialogOpen: boolean
}

export function ProjectRouteContainer({
  workspaceId,
  currentWorkspace,
  currentUser,
  selectedProject,
  selectedProjectId,
  canLoadCurrentWorkspace,
  isLoadingProjects,
  hasProjects,
  canCreateProject,
  onOpenCreateProject,
  issueViewMode,
  boardIssueView,
  isShellDialogOpen,
}: ProjectRouteContainerProps) {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [issueSearchQuery, setIssueSearchQuery] = useState('')
  const [issueWorkflowFilter, setIssueWorkflowFilter] = useState<IssueWorkflowFilter>('ACTIVE')
  const [issuePriorityFilter, setIssuePriorityFilter] = useState<IssuePriority | ''>('')
  const [issueLabelFilter, setIssueLabelFilter] = useState('')
  const [issueAssigneeFilter, setIssueAssigneeFilter] = useState('')
  const [createIssueDefaultWorkflowStateId, setCreateIssueDefaultWorkflowStateId] = useState('')
  const [createIssueDefaultTitle, setCreateIssueDefaultTitle] = useState('')
  const [createIssueDefaultAssigneeUserId, setCreateIssueDefaultAssigneeUserId] = useState('')
  const [isCreateIssueDialogOpen, setIsCreateIssueDialogOpen] = useState(false)
  const [isProjectMembersDialogOpen, setIsProjectMembersDialogOpen] = useState(false)
  const [isProjectWorkflowDialogOpen, setIsProjectWorkflowDialogOpen] = useState(false)
  const debouncedIssueSearchQuery = useDebouncedValue(
    issueSearchQuery,
    ISSUE_SEARCH_DEBOUNCE_MS,
  )

  const {
    projectMembersQuery,
    workspaceMembersQuery,
    projectMembers,
    workspaceMembers,
    activeProjectMembers,
    canManageProjectMembers,
    addableWorkspaceMembers,
  } = useProjectMemberQueries({
    workspaceId,
    projectId: selectedProjectId,
    currentUserId: currentUser?.id ?? null,
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    loadWorkspaceMembers: isProjectMembersDialogOpen,
  })

  const { labels: projectLabels } = useProjectLabelsQuery(
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
    workspaceId,
    projectId: selectedProjectId,
    metadataEnabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    boardEnabled: Boolean(
      canLoadCurrentWorkspace && selectedProjectId && issueViewMode === 'BOARD',
    ),
  })

  const {
    filters: issueFilters,
    filterKey: issueFilterKey,
    hasFilters: hasAppliedIssueFilters,
  } = buildIssueListFilterState({
    searchQuery: debouncedIssueSearchQuery,
    workflowFilter: issueWorkflowFilter,
    priorityFilter: issuePriorityFilter,
    labelFilter: issueLabelFilter,
    assigneeFilter: issueAssigneeFilter,
  })
  const hasIssueFilters = hasAppliedIssueFilters || Boolean(issueSearchQuery.trim())

  const { issuesQuery, issues } = useIssueListQueries({
    workspaceId,
    projectId: selectedProjectId,
    filters: issueFilters,
    filterKey: issueFilterKey,
    enabled: Boolean(
      canLoadCurrentWorkspace && selectedProjectId && issueViewMode === 'LIST',
    ),
  })
  const issueGroups = useMemo(
    () => groupIssuesByWorkflowState(issues, projectWorkflowStates, issueWorkflowFilter),
    [issueWorkflowFilter, issues, projectWorkflowStates],
  )

  const { createIssueMutation, createProjectLabelMutation } = useIssueMutations({
    workspaceId,
    onIssueCreated: (issue) => {
      setIsCreateIssueDialogOpen(false)
      if (workspaceId) {
        navigate(
          pathWithSearchParams(
            issuePath(workspaceId, issue.projectId, issue.id),
            searchParams,
          ),
        )
      }
    },
  })

  const {
    quickCreateIssueMutation,
    reorderIssueMutation,
    createWorkflowStateMutation,
    updateWorkflowStateMutation,
    reorderWorkflowStatesMutation,
  } = useBoardMutations(workspaceId)

  const {
    addMemberMutation,
    updateMemberMutation,
    removeMemberMutation,
    resetMemberMutations,
  } = useProjectMemberMutations({
    workspaceId,
    currentUserId: currentUser?.id ?? null,
    onCurrentUserRemoved: () => setIsProjectMembersDialogOpen(false),
  })

  function openCreateIssueDialog(
    workflowState?: ProjectWorkflowState | null,
    seed: CreateIssueDialogSeed = {},
  ) {
    createIssueMutation.reset()
    createProjectLabelMutation.reset()
    setCreateIssueDefaultWorkflowStateId(
      workflowState?.id ?? defaultWorkflowStateIdForStatus(projectWorkflowStates, 'TODO'),
    )
    setCreateIssueDefaultTitle(seed.title ?? '')
    setCreateIssueDefaultAssigneeUserId(seed.assigneeUserId ?? '')
    setIsCreateIssueDialogOpen(true)
  }

  function closeCreateIssueDialog() {
    if (!createIssueMutation.isPending && !createProjectLabelMutation.isPending) {
      setIsCreateIssueDialogOpen(false)
    }
  }

  function openProjectMembersDialog() {
    resetMemberMutations()
    setIsProjectMembersDialogOpen(true)
  }

  function closeProjectMembersDialog() {
    if (!addMemberMutation.isPending && !updateMemberMutation.isPending && !removeMemberMutation.isPending) {
      setIsProjectMembersDialogOpen(false)
    }
  }

  function openProjectWorkflowDialog() {
    createWorkflowStateMutation.reset()
    updateWorkflowStateMutation.reset()
    reorderWorkflowStatesMutation.reset()
    setIsProjectWorkflowDialogOpen(true)
  }

  function closeProjectWorkflowDialog() {
    if (!createWorkflowStateMutation.isPending && !updateWorkflowStateMutation.isPending && !reorderWorkflowStatesMutation.isPending) {
      setIsProjectWorkflowDialogOpen(false)
    }
  }

  function clearIssueFilters() {
    setIssueSearchQuery('')
    setIssueWorkflowFilter('ACTIVE')
    setIssuePriorityFilter('')
    setIssueLabelFilter('')
    setIssueAssigneeFilter('')
  }

  async function handleReorderIssue({ optimisticBoard, ...request }: KanbanReorder) {
    if (!selectedProjectId) return
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
    if (!selectedProjectId || !trimmedTitle) return null
    quickCreateIssueMutation.reset()
    return quickCreateIssueMutation.mutateAsync({
      projectId: selectedProjectId,
      title: trimmedTitle,
      workflowStateId,
      assigneeUserId,
    })
  }

  function handleCreateIssue(values: CreateIssueFormValues) {
    const title = values.title.trim()
    if (!selectedProjectId || !title) return
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
    if (!selectedProjectId || !name) return null
    createProjectLabelMutation.reset()
    return createProjectLabelMutation.mutateAsync({
      projectId: selectedProjectId,
      name,
      color: values.color,
    })
  }

  async function handleCreateWorkflowState(values: CreateProjectWorkflowStateFormValues) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) return null
    createWorkflowStateMutation.reset()
    return createWorkflowStateMutation.mutateAsync({
      projectId: selectedProjectId,
      name,
      category: values.category,
    })
  }

  async function handleUpdateWorkflowState(
    workflowStateId: string,
    values: UpdateProjectWorkflowStateFormValues,
  ) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) return null
    updateWorkflowStateMutation.reset()
    return updateWorkflowStateMutation.mutateAsync({
      projectId: selectedProjectId,
      workflowStateId,
      values: { name, category: values.category },
    })
  }

  async function handleReorderWorkflowState(workflowStateId: string, direction: -1 | 1) {
    if (!selectedProjectId) return
    const currentIndex = projectWorkflowStates.findIndex((state) => state.id === workflowStateId)
    const nextIndex = currentIndex + direction
    if (currentIndex < 0 || nextIndex < 0 || nextIndex >= projectWorkflowStates.length) return

    const workflowStateIds = projectWorkflowStates.map((state) => state.id)
    ;[workflowStateIds[currentIndex], workflowStateIds[nextIndex]] = [
      workflowStateIds[nextIndex],
      workflowStateIds[currentIndex],
    ]
    reorderWorkflowStatesMutation.reset()
    await reorderWorkflowStatesMutation.mutateAsync({ projectId: selectedProjectId, workflowStateIds })
  }

  async function handleAddProjectMember(values: AddProjectMemberFormValues) {
    const userId = values.userId.trim()
    if (!selectedProjectId || !userId) return
    resetMemberMutations()
    await addMemberMutation.mutateAsync({ projectId: selectedProjectId, userId })
  }

  async function handleUpdateProjectMemberRole(memberId: string, role: 'OWNER' | 'MEMBER') {
    if (!selectedProjectId) return
    resetMemberMutations()
    await updateMemberMutation.mutateAsync({ projectId: selectedProjectId, memberId, role })
  }

  async function handleRemoveProjectMember(member: ProjectMember) {
    if (!selectedProjectId) return
    const memberName = member.displayName || member.email
    const isRemovingSelf = member.userId === currentUser?.id
    const confirmed = window.confirm(
      isRemovingSelf
        ? 'Remove yourself from this project? You will lose access immediately.'
        : `Remove ${memberName} from this project?`,
    )
    if (!confirmed) return
    resetMemberMutations()
    await removeMemberMutation.mutateAsync({
      projectId: selectedProjectId,
      memberId: member.id,
      userId: member.userId,
    })
  }

  return (
    <>
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
          selectedIssueId={null}
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
          canUseQuickCreateShortcut={!isShellDialogOpen && !isCreateIssueDialogOpen && !isProjectMembersDialogOpen && !isProjectWorkflowDialogOpen}
          isLoadingProjectMembers={projectMembersQuery.isLoading}
          isLoadingProjects={isLoadingProjects}
          hasProjects={hasProjects}
          canCreateProject={canCreateProject}
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
          onIssueViewModeChange={(viewMode) => setSearchParams(workViewSearchParams(searchParams, viewMode, boardIssueView))}
          onClearIssueFilters={clearIssueFilters}
          onOpenProjectMembers={openProjectMembersDialog}
          onOpenProjectWorkflow={openProjectWorkflowDialog}
          onOpenCreateProject={onOpenCreateProject}
          onOpenCreateIssue={openCreateIssueDialog}
          onIssueSelect={(issueId) => {
            if (workspaceId && selectedProjectId) {
              navigate(pathWithSearchParams(issuePath(workspaceId, selectedProjectId, issueId), searchParams))
            }
          }}
          onReorderIssue={handleReorderIssue}
          onQuickCreateIssue={handleQuickCreateIssue}
          onResetQuickCreateIssue={() => quickCreateIssueMutation.reset()}
        />
      </Suspense>

      {isCreateIssueDialogOpen ? (
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
            onClose={closeCreateIssueDialog}
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
            isSubmitting={addMemberMutation.isPending}
            isMutating={addMemberMutation.isPending || updateMemberMutation.isPending || removeMemberMutation.isPending}
            updatingMemberId={updateMemberMutation.isPending ? (updateMemberMutation.variables?.memberId ?? null) : null}
            removingMemberId={removeMemberMutation.isPending ? (removeMemberMutation.variables?.memberId ?? null) : null}
            addError={addMemberMutation.error}
            updateError={updateMemberMutation.error}
            removeError={removeMemberMutation.error}
          />
        </Suspense>
      ) : null}

      {isProjectWorkflowDialogOpen ? (
        <Suspense fallback={null}>
          <ProjectWorkflowDialog
            isOpen
            selectedProject={selectedProject}
            workflowStates={projectWorkflowStates}
            onSubmit={handleCreateWorkflowState}
            onUpdate={handleUpdateWorkflowState}
            onReorder={handleReorderWorkflowState}
            onClose={closeProjectWorkflowDialog}
            canCreateWorkflowStates={canManageProjectMembers}
            isLoadingWorkflowStates={projectWorkflowStatesQuery.isLoading}
            workflowStatesError={projectWorkflowStatesQuery.error}
            isSubmitting={createWorkflowStateMutation.isPending}
            isUpdating={updateWorkflowStateMutation.isPending}
            isReordering={reorderWorkflowStatesMutation.isPending}
            error={createWorkflowStateMutation.error ?? updateWorkflowStateMutation.error ?? reorderWorkflowStatesMutation.error}
          />
        </Suspense>
      ) : null}
    </>
  )
}
