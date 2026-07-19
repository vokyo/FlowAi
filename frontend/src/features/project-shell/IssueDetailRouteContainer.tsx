import { lazy, Suspense } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import type { AuthUser, AuthWorkspace } from '@/auth/auth-api'
import { useBoardQueries } from '@/features/board/useBoardQueries'
import { useIssueDetailMutations } from '@/features/issue-detail/useIssueDetailMutations'
import { useIssueDetailQueries } from '@/features/issue-detail/useIssueDetailQueries'
import { useIssueListQueries } from '@/features/issue-list/useIssueListQueries'
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
  const [searchParams] = useSearchParams()
  const enabled = Boolean(canLoadCurrentWorkspace && selectedProjectId)
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
  const { issues } = useIssueListQueries({
    workspaceId,
    projectId: selectedProjectId,
    filters: {},
    filterKey: ['ACTIVE', '', '', '', ''],
    enabled,
  })
  const selectedIssueSummary = issues.find((issue) => issue.id === issueId) ?? null

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
  const activeIssue = issueQuery.data ?? selectedIssueSummary

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

  return (
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
      />
    </Suspense>
  )
}
