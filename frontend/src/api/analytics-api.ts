import { api } from '@/api/client'

export type AnalyticsRangeDays = 7 | 30 | 90

export type AnalyticsStatusCategory = 'TODO' | 'IN_PROGRESS' | 'DONE'

export type AnalyticsStatusDistribution = {
  category: AnalyticsStatusCategory
  count: number
}

export type AnalyticsAssigneeDistribution = {
  userId: string | null
  displayName: string
  email: string | null
  count: number
}

export type AnalyticsCompletionTrendPoint = {
  date: string
  count: number
}

export type AnalyticsOverview = {
  projectId: string
  rangeDays: AnalyticsRangeDays
  totalIssues: number
  completedIssues: number
  completionRate: number
  archivedIssues: number
  statusDistribution: AnalyticsStatusDistribution[]
  assigneeDistribution: AnalyticsAssigneeDistribution[]
  completionTrend: AnalyticsCompletionTrendPoint[]
}

export function getProjectAnalytics(
  projectId: string,
  days: AnalyticsRangeDays = 30,
) {
  const params = new URLSearchParams({
    projectId,
    days: String(days),
  })

  return api.get<AnalyticsOverview>(`/analytics/overview?${params.toString()}`)
}
