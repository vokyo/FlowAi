import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '@/lib/query-keys'
import { getProjectAnalytics, type AnalyticsRangeDays } from '@/api/analytics-api'

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
    queryKey: queryKeys.analyticsRange(workspaceId, projectId, rangeDays),
    queryFn: () => getProjectAnalytics(projectId ?? '', rangeDays),
    enabled,
    retry: false,
  })
}
