import { lazy, Suspense } from 'react'

const ProjectShellPage = lazy(() =>
  import('@/features/project-shell/ProjectShellPage').then((module) => ({
    default: module.ProjectShellPage,
  })),
)

type AppPageProps = {
  onSignOut: () => void
  onSessionChanged: () => void
}

export function AppPage(props: AppPageProps) {
  return (
    <Suspense fallback={<main className="app-shell" aria-busy="true" />}>
      <ProjectShellPage {...props} />
    </Suspense>
  )
}
