import type { AnalyticsRangeDays } from '@/analytics/analytics-api'
import type { BoardIssueView } from '@/work/board-utils'

export type IssueViewMode = 'BOARD' | 'LIST'

export function issueViewModeFromSearchParams(searchParams: URLSearchParams): IssueViewMode {
  return searchParams.get('layout') === 'list' ? 'LIST' : 'BOARD'
}

export function boardIssueViewFromSearchParams(searchParams: URLSearchParams): BoardIssueView {
  const view = searchParams.get('view')
  if (view === 'mine') return 'MINE'
  if (view === 'unassigned') return 'UNASSIGNED'
  return 'ALL'
}

export function analyticsRangeFromSearchParams(
  searchParams: URLSearchParams,
): AnalyticsRangeDays {
  const range = searchParams.get('range')
  if (range === '7') return 7
  if (range === '90') return 90
  return 30
}

export function normalizeAppSearchParams(
  searchParams: URLSearchParams,
  isAnalyticsRoute: boolean,
) {
  const normalized = normalizeWorkViewSearchParams(searchParams)
  if (!isAnalyticsRoute) {
    normalized.delete('range')
    return normalized
  }

  const range = normalized.get('range')
  if (range !== '7' && range !== '90') normalized.delete('range')
  return normalized
}

export function normalizeWorkViewSearchParams(searchParams: URLSearchParams) {
  const normalized = new URLSearchParams(searchParams)
  if (normalized.get('layout') !== 'list') normalized.delete('layout')

  const view = normalized.get('view')
  if (view !== 'mine' && view !== 'unassigned') normalized.delete('view')
  return normalized
}

export function issueViewSearchParams(searchParams: URLSearchParams) {
  const normalized = normalizeWorkViewSearchParams(searchParams)
  normalized.delete('range')
  return normalized
}

export function workViewSearchParams(
  searchParams: URLSearchParams,
  layout: IssueViewMode,
  view: BoardIssueView,
) {
  const next = new URLSearchParams(searchParams)
  if (layout === 'LIST') next.set('layout', 'list')
  else next.delete('layout')

  if (view === 'MINE') next.set('view', 'mine')
  else if (view === 'UNASSIGNED') next.set('view', 'unassigned')
  else next.delete('view')

  next.delete('range')
  return next
}

export function analyticsSearchParams(
  searchParams: URLSearchParams,
  rangeDays: AnalyticsRangeDays,
) {
  const next = normalizeWorkViewSearchParams(searchParams)
  if (rangeDays === 30) next.delete('range')
  else next.set('range', String(rangeDays))
  return next
}

export function pathWithSearchParams(
  path: string,
  searchParams: URLSearchParams | string,
) {
  const searchString =
    typeof searchParams === 'string'
      ? searchParams.replace(/^\?/, '')
      : searchParams.toString()
  return searchString ? `${path}?${searchString}` : path
}

export function projectPath(workspaceId: string, projectId: string) {
  return `/app/workspaces/${workspaceId}/projects/${projectId}`
}

export function projectAnalyticsPath(workspaceId: string, projectId: string) {
  return `${projectPath(workspaceId, projectId)}/analytics`
}

export function issuePath(workspaceId: string, projectId: string, issueId: string) {
  return `${projectPath(workspaceId, projectId)}/issues/${issueId}`
}

export function isProjectAnalyticsPath(pathname: string) {
  return pathname.endsWith('/analytics')
}
