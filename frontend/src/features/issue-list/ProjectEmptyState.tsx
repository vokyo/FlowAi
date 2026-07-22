import { FolderKanban, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function ProjectEmptyState({
  hasProjects,
  canCreateProject,
  onCreateProject,
}: {
  hasProjects: boolean
  canCreateProject: boolean
  onCreateProject: () => void
}) {
  const title = hasProjects
    ? 'Choose a project'
    : canCreateProject
      ? 'Create your first project'
      : 'No projects available'
  const body = hasProjects
    ? 'Select a project from the sidebar to open its board, issues, and analytics.'
    : canCreateProject
      ? 'Bring issues, workflow states, and teammates together in one focused workspace.'
      : 'You do not have access to a project in this workspace yet. Ask an owner or admin to add you.'

  return (
    <section className="project-empty-state" aria-labelledby="project-empty-title">
      <span className="project-empty-icon"><FolderKanban aria-hidden="true" /></span>
      <span className="project-empty-eyebrow">
        {hasProjects ? 'Project navigation' : 'Start organizing work'}
      </span>
      <h2 id="project-empty-title">{title}</h2>
      <p>{body}</p>
      {!hasProjects && canCreateProject ? (
        <Button type="button" onClick={onCreateProject}>
          <Plus aria-hidden="true" />
          Create project
        </Button>
      ) : null}
    </section>
  )
}
