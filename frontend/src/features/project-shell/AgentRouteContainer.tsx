import { lazy, Suspense } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { getAiStatus } from '@/api/ai-api'
import type { Project } from '@/api/work-api'
import {
  agentRunPath,
  issueViewSearchParams,
  pathWithSearchParams,
  projectAgentPath,
} from '@/routing/route-utils'
import { InlineState } from '@/ui/feature-ui'

const PlanningAgentView = lazy(() =>
  import('@/features/agent/PlanningAgentView').then((module) => ({
    default: module.PlanningAgentView,
  })),
)

type AgentRouteContainerProps = {
  workspaceId: string | null
  selectedProject: Project | null
  selectedProjectId: string | null
  runId: string | null
  canLoadCurrentWorkspace: boolean
}

/** The run that is open lives in the URL, so a refresh or a shared link reopens it. */
export function AgentRouteContainer({
  workspaceId,
  selectedProject,
  selectedProjectId,
  runId,
  canLoadCurrentWorkspace,
}: AgentRouteContainerProps) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const aiStatusQuery = useQuery({
    queryKey: ['ai-status'],
    queryFn: getAiStatus,
    enabled: Boolean(canLoadCurrentWorkspace),
    retry: false,
    staleTime: 60_000,
  })

  if (!workspaceId || !selectedProjectId || !canLoadCurrentWorkspace) {
    return <InlineState>Loading the project.</InlineState>
  }

  return (
    <Suspense fallback={<InlineState>Loading the planning agent.</InlineState>}>
      <PlanningAgentView
        workspaceId={workspaceId}
        project={selectedProject}
        projectId={selectedProjectId}
        runId={runId}
        available={Boolean(aiStatusQuery.data?.agentAvailable)}
        isLoadingStatus={aiStatusQuery.isLoading}
        onOpenRun={(nextRunId) => {
          const path = nextRunId
            ? agentRunPath(workspaceId, selectedProjectId, nextRunId)
            : projectAgentPath(workspaceId, selectedProjectId)
          navigate(pathWithSearchParams(path, issueViewSearchParams(searchParams)))
        }}
      />
    </Suspense>
  )
}
