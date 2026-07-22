import { lazy, Suspense } from 'react'
import { useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { getAiStatus } from '@/ai/ai-api'
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

const ProjectSummaryPanel = lazy(() =>
  import('@/features/ai-copilot/ProjectSummaryPanel').then((module) => ({
    default: module.ProjectSummaryPanel,
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
  const summarySuggestionId = searchParams.get('aiProjectSummary')
  const aiStatusQuery = useQuery({
    queryKey: ['ai-status'],
    queryFn: getAiStatus,
    enabled: Boolean(canLoadCurrentWorkspace),
    retry: false,
    staleTime: 60_000,
  })
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
        aiSummary={selectedProjectId ? (
          <ProjectSummaryPanel
            workspaceId={workspaceId}
            projectId={selectedProjectId}
            rangeDays={rangeDays}
            suggestionId={summarySuggestionId}
            available={Boolean(aiStatusQuery.data?.projectSummaryAvailable)}
            isLoadingStatus={aiStatusQuery.isLoading}
            onSuggestionChange={(suggestionId) => {
              const next = new URLSearchParams(searchParams)
              if (suggestionId) next.set('aiProjectSummary', suggestionId)
              else next.delete('aiProjectSummary')
              setSearchParams(next)
            }}
          />
        ) : undefined}
      />
    </Suspense>
  )
}
