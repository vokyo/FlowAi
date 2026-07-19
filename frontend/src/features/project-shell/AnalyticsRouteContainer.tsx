import { lazy, Suspense } from 'react'
import { useSearchParams } from 'react-router'
import { useProjectAnalyticsQuery } from '@/analytics/useProjectAnalyticsQuery'
import { InlineState } from '@/features/project-shell/feature-ui'
import {
  analyticsRangeFromSearchParams,
  analyticsSearchParams,
} from '@/features/project-shell/route-utils'
import type { Project } from '@/work/work-api'

const ProjectAnalyticsView = lazy(() =>
  import('@/analytics/ProjectAnalyticsView').then((module) => ({
    default: module.ProjectAnalyticsView,
  })),
)

type AnalyticsRouteContainerProps = {
  workspaceId: string | null
  selectedProject: Project | null
  selectedProjectId: string | null
  canLoadCurrentWorkspace: boolean
}

export function AnalyticsRouteContainer({
  workspaceId,
  selectedProject,
  selectedProjectId,
  canLoadCurrentWorkspace,
}: AnalyticsRouteContainerProps) {
  const [searchParams, setSearchParams] = useSearchParams()
  const rangeDays = analyticsRangeFromSearchParams(searchParams)
  const analyticsQuery = useProjectAnalyticsQuery({
    workspaceId,
    projectId: selectedProjectId,
    rangeDays,
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
  })

  return (
    <Suspense fallback={<InlineState>Loading analytics.</InlineState>}>
      <ProjectAnalyticsView
        project={selectedProject}
        overview={analyticsQuery.data ?? null}
        rangeDays={rangeDays}
        isLoading={analyticsQuery.isLoading}
        error={analyticsQuery.error}
        onRangeChange={(nextRangeDays) => {
          setSearchParams(analyticsSearchParams(searchParams, nextRangeDays))
        }}
      />
    </Suspense>
  )
}
