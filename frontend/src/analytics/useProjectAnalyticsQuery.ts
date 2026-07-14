import { useQuery } from '@tanstack/react-query'
import { getProjectAnalytics, type AnalyticsRangeDays } from './analytics-api'

export function useProjectAnalyticsQuery({
  workspaceId,
  projectId,
  rangeDays,
  enabled,
}: {
  workspaceId: string | null
  projectId: string | null
  rangeDays: AnalyticsRangeDays
  enabled: boolean
}) {
  return useQuery({
    queryKey: ['project-analytics', workspaceId, projectId, rangeDays],
    queryFn: () => getProjectAnalytics(projectId ?? '', rangeDays),
    enabled,
    retry: false,
  })
}
