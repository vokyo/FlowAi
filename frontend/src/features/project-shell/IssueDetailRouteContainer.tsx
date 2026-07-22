import { lazy, Suspense } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router'
import { getAiStatus } from '@/ai/ai-api'
import type { AuthUser, AuthWorkspace } from '@/auth/auth-api'
import { useBoardQueries } from '@/features/board/useBoardQueries'
import { useIssueDetailMutations } from '@/features/issue-detail/useIssueDetailMutations'
import { useIssueDetailQueries } from '@/features/issue-detail/useIssueDetailQueries'
import { useProjectLabelsQuery } from '@/features/issue-list/useProjectLabelsQuery'
import { useProjectMemberQueries } from '@/features/project-members/useProjectMemberQueries'
import { InlineState } from '@/features/project-shell/feature-ui'
import type { CommentFormValues } from '@/features/project-shell/project-model'
import { pathWithSearchParams, projectPath } from '@/features/project-shell/route-utils'
import type { Project, UpdateIssueRequest } from '@/work/work-api'

const IssueDetailFeature = lazy(() =>
  import('@/features/issue-detail/IssueDetailFeature').then((module) => ({
    default: module.IssueDetailFeature,
  })),
)

const IssueCopilotDrawer = lazy(() =>
  import('@/features/ai-copilot/IssueCopilotDrawer').then((module) => ({
    default: module.IssueCopilotDrawer,
  })),
)

const IssueSummaryDrawer = lazy(() =>
  import('@/features/ai-copilot/IssueSummaryDrawer').then((module) => ({
    default: module.IssueSummaryDrawer,
  })),
)

type IssueDetailRouteContainerProps = {
  workspaceId: string | null
  currentWorkspace: AuthWorkspace | null
  currentUser: AuthUser | null
  selectedProject: Project | null
  selectedProjectId: string | null
  issueId: string
  canLoadCurrentWorkspace: boolean
}

export function IssueDetailRouteContainer({
  workspaceId,
  currentWorkspace,
  currentUser,
  selectedProject,
  selectedProjectId,
  issueId,
  canLoadCurrentWorkspace,
}: IssueDetailRouteContainerProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const enabled = Boolean(canLoadCurrentWorkspace && selectedProjectId)
  const copilotMode = searchParams.get('copilot')
  const suggestionId = searchParams.get('aiSuggestion')
  const suggestionType = searchParams.get('aiSuggestionType')
  const aiStatusQuery = useQuery({
    queryKey: ['ai-status'],
    queryFn: getAiStatus,
    enabled: Boolean(canLoadCurrentWorkspace),
    retry: false,
    staleTime: 60_000,
  })
  const { activeProjectMembers } = useProjectMemberQueries({
    workspaceId,
    projectId: selectedProjectId,
    currentUserId: currentUser?.id ?? null,
    enabled,
    loadWorkspaceMembers: false,
  })
  const { labels: projectLabels } = useProjectLabelsQuery(selectedProjectId, enabled)
  const { workflowStates: projectWorkflowStates } = useBoardQueries({
    workspaceId,
    projectId: selectedProjectId,
    metadataEnabled: enabled,
    boardEnabled: false,
  })
  const {
    issueQuery,
    commentsQuery,
    activitiesQuery,
    comments,
    activities,
  } = useIssueDetailQueries({
    workspaceId,
    issueId,
    enabled: Boolean(enabled && selectedProject),
  })
  const { createCommentMutation, updateIssueMutation } = useIssueDetailMutations(workspaceId)
  const activeIssue = issueQuery.data ?? null

  async function handleCreateComment(values: CommentFormValues) {
    const body = values.body.trim()
    if (!selectedProjectId || !body) return
    await createCommentMutation.mutateAsync({ issueId, projectId: selectedProjectId, body })
  }

  async function handleUpdateIssue(request: UpdateIssueRequest) {
    if (!selectedProjectId) return
    updateIssueMutation.reset()
    await updateIssueMutation.mutateAsync({ issueId, projectId: selectedProjectId, request })
  }

  async function handleArchiveIssue() {
    if (!window.confirm('Archive this issue? It will be hidden from the active issue list.')) return
    try {
      await handleUpdateIssue({ status: 'ARCHIVED' })
    } catch {
      // The shared update error is rendered in the detail panel.
    }
  }

  function openBreakdownCopilot() {
    const next = new URLSearchParams(searchParams)
    if (suggestionType !== 'breakdown') next.delete('aiSuggestion')
    next.set('copilot', 'breakdown')
    next.set('aiSuggestionType', 'breakdown')
    setSearchParams(next)
  }

  function openSummaryCopilot() {
    const next = new URLSearchParams(searchParams)
    if (suggestionType !== 'issue-summary') next.delete('aiSuggestion')
    next.set('copilot', 'issue-summary')
    next.set('aiSuggestionType', 'issue-summary')
    setSearchParams(next)
  }

  function closeCopilot() {
    const next = new URLSearchParams(searchParams)
    next.delete('copilot')
    setSearchParams(next)
  }

  function changeBreakdownSuggestion(nextSuggestionId: string | null) {
    const next = new URLSearchParams(searchParams)
    next.set('copilot', 'breakdown')
    next.set('aiSuggestionType', 'breakdown')
    if (nextSuggestionId) next.set('aiSuggestion', nextSuggestionId)
    else next.delete('aiSuggestion')
    setSearchParams(next)
  }

  function changeSummarySuggestion(nextSuggestionId: string) {
    const next = new URLSearchParams(searchParams)
    next.set('copilot', 'issue-summary')
    next.set('aiSuggestionType', 'issue-summary')
    next.set('aiSuggestion', nextSuggestionId)
    setSearchParams(next)
  }

  async function refreshAfterApply() {
    if (!workspaceId || !selectedProjectId) return
    queryClient.removeQueries({
      queryKey: ['project-board-column', workspaceId, selectedProjectId],
    })
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['issue', workspaceId, issueId] }),
      queryClient.invalidateQueries({ queryKey: ['issue-activities', workspaceId, issueId] }),
      queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, selectedProjectId] }),
      queryClient.invalidateQueries({ queryKey: ['project-board', workspaceId, selectedProjectId] }),
      queryClient.invalidateQueries({ queryKey: ['project-analytics', workspaceId, selectedProjectId] }),
    ])
  }

  return (
    <Suspense fallback={<div className="content-page"><InlineState>Loading issue detail.</InlineState></div>}>
      <>
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
          isLoadingIssue={issueQuery.isLoading}
          issueError={issueQuery.error}
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
            if (workspaceId && selectedProjectId) {
              navigate(pathWithSearchParams(projectPath(workspaceId, selectedProjectId), searchParams))
            }
          }}
          onUpdateIssue={handleUpdateIssue}
          onArchiveIssue={handleArchiveIssue}
          isUpdatingIssue={updateIssueMutation.isPending}
          updateIssueError={updateIssueMutation.error}
          onResetUpdateIssueError={() => updateIssueMutation.reset()}
          aiBreakdownAvailable={Boolean(
            aiStatusQuery.data?.breakdownAvailable
            && activeIssue?.status !== 'ARCHIVED'
          )}
          aiIssueSummaryAvailable={Boolean(aiStatusQuery.data?.issueSummaryAvailable)}
          isLoadingAiStatus={aiStatusQuery.isLoading}
          onOpenAiBreakdown={openBreakdownCopilot}
          onOpenAiSummary={openSummaryCopilot}
        />
        {selectedProjectId ? (
          <IssueCopilotDrawer
            open={copilotMode === 'breakdown'}
            workspaceId={workspaceId}
            issueId={issueId}
            suggestionId={suggestionType === 'breakdown' ? suggestionId : null}
            projectMembers={activeProjectMembers}
            projectLabels={projectLabels}
            workflowStates={projectWorkflowStates}
            onSuggestionChange={changeBreakdownSuggestion}
            onClose={closeCopilot}
            onApplied={async () => refreshAfterApply()}
          />
        ) : null}
        <IssueSummaryDrawer
          open={copilotMode === 'issue-summary'}
          workspaceId={workspaceId}
          issueId={issueId}
          suggestionId={suggestionType === 'issue-summary' ? suggestionId : null}
          onSuggestionChange={changeSummarySuggestion}
          onClose={closeCopilot}
        />
      </>
    </Suspense>
  )
}
