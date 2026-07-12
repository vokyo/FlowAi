import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { AnalyticsOverview } from './analytics-api'
import { ProjectAnalyticsView } from './ProjectAnalyticsView'

const project = {
  id: 'project-1',
  name: 'Launch project',
  description: 'Analytics test project',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const overview: AnalyticsOverview = {
  projectId: project.id,
  rangeDays: 7,
  totalIssues: 3,
  completedIssues: 1,
  completionRate: 1 / 3,
  archivedIssues: 2,
  statusDistribution: [
    { category: 'TODO', count: 1 },
    { category: 'IN_PROGRESS', count: 1 },
    { category: 'DONE', count: 1 },
  ],
  assigneeDistribution: [
    {
      userId: 'user-1',
      displayName: 'Ada Lovelace',
      email: 'ada@example.com',
      count: 2,
    },
    {
      userId: null,
      displayName: 'Unassigned',
      email: null,
      count: 1,
    },
  ],
  completionTrend: [
    { date: '2026-01-01', count: 0 },
    { date: '2026-01-02', count: 0 },
    { date: '2026-01-03', count: 0 },
    { date: '2026-01-04', count: 0 },
    { date: '2026-01-05', count: 0 },
    { date: '2026-01-06', count: 0 },
    { date: '2026-01-07', count: 1 },
  ],
}

describe('ProjectAnalyticsView', () => {
  it('renders project metrics and changes the requested date range', async () => {
    const user = userEvent.setup()
    const onRangeChange = vi.fn()
    render(
      <ProjectAnalyticsView
        project={project}
        overview={overview}
        rangeDays={7}
        isLoading={false}
        error={null}
        onRangeChange={onRangeChange}
      />,
    )

    const summary = screen.getByLabelText('Analytics summary')
    expect(within(summary).getByText('Active issues')).toBeInTheDocument()
    expect(within(summary).getByText('3')).toBeInTheDocument()
    expect(within(summary).getByText('Completed')).toBeInTheDocument()
    expect(within(summary).getByText('33.3%')).toBeInTheDocument()
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument()
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
    expect(screen.getByRole('img', {
      name: 'Completed issues by UTC date over 7 days',
    })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '30d' }))
    expect(onRangeChange).toHaveBeenCalledWith(30)
  })

  it('renders explicit loading and error states', () => {
    const { rerender } = render(
      <ProjectAnalyticsView
        project={project}
        overview={null}
        rangeDays={30}
        isLoading
        error={null}
        onRangeChange={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('Loading analytics')).toBeInTheDocument()

    rerender(
      <ProjectAnalyticsView
        project={project}
        overview={null}
        rangeDays={30}
        isLoading={false}
        error={new Error('Analytics unavailable')}
        onRangeChange={vi.fn()}
      />,
    )
    expect(screen.getByText('Analytics could not be loaded.')).toBeInTheDocument()
    expect(screen.getByText('Analytics unavailable')).toBeInTheDocument()
  })
})
