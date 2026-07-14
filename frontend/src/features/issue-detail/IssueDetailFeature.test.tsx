import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { IssueDetailFeature } from './IssueDetailFeature'
import type { IssueSummary, Project, ProjectWorkflowState } from '@/work/work-api'

const user = { id: 'user-1', email: 'owner@example.com', displayName: 'Owner' }
const workflowState: ProjectWorkflowState = {
  id: 'state-1',
  projectId: 'project-1',
  name: 'Todo',
  category: 'TODO',
  position: 0,
  createdAt: '2026-07-14T00:00:00Z',
  updatedAt: '2026-07-14T00:00:00Z',
}
const project: Project = {
  id: 'project-1',
  name: 'Project',
  createdAt: '2026-07-14T00:00:00Z',
  updatedAt: '2026-07-14T00:00:00Z',
}
const issue: IssueSummary = {
  id: 'issue-1',
  projectId: project.id,
  title: 'Paginated detail',
  status: 'TODO',
  workflowState,
  labels: [],
  creator: user,
  assignee: null,
  boardPosition: 10_000,
  createdAt: '2026-07-14T00:00:00Z',
  updatedAt: '2026-07-14T00:00:00Z',
}

describe('IssueDetailFeature pagination controls', () => {
  it('renders loaded comments and activity and requests their earlier pages', async () => {
    const onLoadMoreComments = vi.fn()
    const onLoadMoreActivities = vi.fn()
    const actor = user

    render(
      <IssueDetailFeature
        issue={issue}
        selectedProject={project}
        projectMembers={[]}
        projectLabels={[]}
        projectWorkflowStates={[workflowState]}
        currentWorkspace={{ id: 'workspace-1', name: 'Workspace', slug: 'workspace', role: 'OWNER' }}
        currentUser={user}
        comments={[{ id: 'comment-1', issueId: issue.id, author: user, body: 'Loaded comment', createdAt: '2026-07-14T01:00:00Z' }]}
        activities={[{ id: 'activity-1', eventType: 'COMMENT_CREATED', actor, createdAt: '2026-07-14T01:00:00Z' }]}
        isLoadingIssue={false}
        issueError={null}
        isLoadingComments={false}
        commentsError={null}
        hasMoreComments
        isLoadingMoreComments={false}
        onLoadMoreComments={onLoadMoreComments}
        isLoadingActivities={false}
        activitiesError={null}
        hasMoreActivities
        isLoadingMoreActivities={false}
        onLoadMoreActivities={onLoadMoreActivities}
        onSubmitComment={vi.fn(async () => undefined)}
        isSubmittingComment={false}
        commentError={null}
        onBackToProject={vi.fn()}
        onUpdateIssue={vi.fn(async () => undefined)}
        onArchiveIssue={vi.fn(async () => undefined)}
        isUpdatingIssue={false}
        updateIssueError={null}
        onResetUpdateIssueError={vi.fn()}
      />,
    )

    expect(screen.getByText('Loaded comment')).toBeInTheDocument()
    expect(screen.getByText('Owner commented')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Load earlier comments' }))
    await userEvent.click(screen.getByRole('button', { name: 'Load earlier activity' }))
    expect(onLoadMoreComments).toHaveBeenCalledOnce()
    expect(onLoadMoreActivities).toHaveBeenCalledOnce()
  })
})
