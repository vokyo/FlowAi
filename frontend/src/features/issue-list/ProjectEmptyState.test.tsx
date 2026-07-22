import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ProjectEmptyState } from './ProjectEmptyState'

describe('ProjectEmptyState', () => {
  it('guides an eligible member to create the first project', async () => {
    const onCreateProject = vi.fn()
    render(
      <ProjectEmptyState
        hasProjects={false}
        canCreateProject
        onCreateProject={onCreateProject}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Create your first project' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Create project' }))
    expect(onCreateProject).toHaveBeenCalledOnce()
  })

  it('explains how to get access when project creation is unavailable', () => {
    render(
      <ProjectEmptyState
        hasProjects={false}
        canCreateProject={false}
        onCreateProject={vi.fn()}
      />,
    )

    expect(screen.getByRole('heading', { name: 'No projects available' })).toBeInTheDocument()
    expect(screen.getByText(/Ask an owner or admin/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create project' })).not.toBeInTheDocument()
  })
})
