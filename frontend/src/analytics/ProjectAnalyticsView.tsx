import {
  Archive,
  ChartColumn,
  CheckCircle2,
  Gauge,
  ListTodo,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { Project } from '@/work/work-api'
import type {
  AnalyticsAssigneeDistribution,
  AnalyticsOverview,
  AnalyticsRangeDays,
  AnalyticsStatusCategory,
} from './analytics-api'

const RANGE_OPTIONS: AnalyticsRangeDays[] = [7, 30, 90]
const STATUS_LABELS: Record<AnalyticsStatusCategory, string> = {
  TODO: 'Todo',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
}

type ProjectAnalyticsViewProps = {
  project: Project | null
  overview: AnalyticsOverview | null
  rangeDays: AnalyticsRangeDays
  isLoading: boolean
  error: Error | null
  onRangeChange: (rangeDays: AnalyticsRangeDays) => void
  aiSummary?: React.ReactNode
}

export function ProjectAnalyticsView({
  project,
  overview,
  rangeDays,
  isLoading,
  error,
  onRangeChange,
  aiSummary,
}: ProjectAnalyticsViewProps) {
  return (
    <div className="content-page analytics-page">
      <header className="content-header analytics-header">
        <div>
          <p className="breadcrumb-line">
            <span>{project?.name ?? 'Project'}</span>
            <span aria-hidden="true">/</span>
            <span>Analytics</span>
          </p>
          <h1>Analytics</h1>
          <p>Issue progress and workload for this project.</p>
        </div>
        <div className="analytics-range-switch" aria-label="Analytics date range">
          {RANGE_OPTIONS.map((option) => (
            <Button
              key={option}
              type="button"
              size="sm"
              variant={rangeDays === option ? 'secondary' : 'ghost'}
              aria-pressed={rangeDays === option}
              onClick={() => onRangeChange(option)}
            >
              {option}d
            </Button>
          ))}
        </div>
      </header>

      {aiSummary}

      {isLoading && !overview ? <AnalyticsLoadingState /> : null}
      {error ? <AnalyticsErrorState error={error} /> : null}
      {overview ? <AnalyticsContent overview={overview} /> : null}
    </div>
  )
}

function AnalyticsContent({ overview }: { overview: AnalyticsOverview }) {
  return (
    <div className="analytics-content">
      <section className="analytics-metrics" aria-label="Analytics summary">
        <Metric
          icon={<ListTodo aria-hidden="true" />}
          label="Active issues"
          value={formatNumber(overview.totalIssues)}
        />
        <Metric
          icon={<CheckCircle2 aria-hidden="true" />}
          label="Completed"
          value={formatNumber(overview.completedIssues)}
        />
        <Metric
          icon={<Gauge aria-hidden="true" />}
          label="Completion rate"
          value={formatPercent(overview.completionRate)}
        />
        <Metric
          icon={<Archive aria-hidden="true" />}
          label="Archived"
          value={formatNumber(overview.archivedIssues)}
        />
      </section>

      <section className="analytics-panel analytics-trend-panel">
        <AnalyticsSectionHeader
          title="Completion trend"
          description={`Issues currently completed during the last ${overview.rangeDays} days.`}
        />
        <CompletionTrend overview={overview} />
      </section>

      <div className="analytics-secondary-grid">
        <section className="analytics-panel">
          <AnalyticsSectionHeader
            title="Status distribution"
            description="Active issues grouped by workflow category."
          />
          <StatusDistribution overview={overview} />
        </section>

        <section className="analytics-panel">
          <AnalyticsSectionHeader
            title="Assignee distribution"
            description="Active issues grouped by current assignee."
          />
          <AssigneeDistribution overview={overview} />
        </section>
      </div>
    </div>
  )
}

function Metric({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode
  label: string
  value: string
}) {
  return (
    <article className="analytics-metric">
      <span className="analytics-metric-icon">{icon}</span>
      <div>
        <span>{label}</span>
        <strong>{value}</strong>
      </div>
    </article>
  )
}

function AnalyticsSectionHeader({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <header className="analytics-section-header">
      <div>
        <h2>{title}</h2>
        <p>{description}</p>
      </div>
      <ChartColumn aria-hidden="true" />
    </header>
  )
}

function CompletionTrend({ overview }: { overview: AnalyticsOverview }) {
  const maxCount = Math.max(1, ...overview.completionTrend.map((point) => point.count))
  const hasCompletions = overview.completionTrend.some((point) => point.count > 0)
  const labelIndexes = new Set([
    0,
    Math.floor((overview.completionTrend.length - 1) / 2),
    overview.completionTrend.length - 1,
  ])

  return (
    <div className="analytics-trend-wrap">
      <div
        className="analytics-trend-chart"
        role="img"
        aria-label={`Completed issues by UTC date over ${overview.rangeDays} days`}
        style={{
          gridTemplateColumns: `repeat(${overview.completionTrend.length}, minmax(3px, 1fr))`,
        }}
      >
        {overview.completionTrend.map((point, index) => (
          <div
            className="analytics-trend-column"
            key={point.date}
            data-edge={
              index === 0
                ? 'first'
                : index === overview.completionTrend.length - 1
                  ? 'last'
                  : undefined
            }
            title={`${formatUtcDate(point.date, true)}: ${point.count}`}
            aria-label={`${formatUtcDate(point.date, true)}: ${point.count} completed`}
          >
            <span
              className="analytics-trend-bar"
              data-empty={point.count === 0}
              style={{ height: `${(point.count / maxCount) * 100}%` }}
            />
            {labelIndexes.has(index) ? (
              <small>{formatUtcDate(point.date)}</small>
            ) : null}
          </div>
        ))}
      </div>
      {!hasCompletions ? (
        <p className="analytics-chart-empty">No completed issues in this range.</p>
      ) : null}
    </div>
  )
}

function StatusDistribution({ overview }: { overview: AnalyticsOverview }) {
  const maxCount = Math.max(1, ...overview.statusDistribution.map((item) => item.count))

  return (
    <div className="analytics-bar-list">
      {overview.statusDistribution.map((item) => (
        <DistributionBar
          key={item.category}
          label={STATUS_LABELS[item.category]}
          value={item.count}
          max={maxCount}
          color={`var(--analytics-${item.category.toLowerCase().replace('_', '-')})`}
        />
      ))}
    </div>
  )
}

function AssigneeDistribution({ overview }: { overview: AnalyticsOverview }) {
  const maxCount = Math.max(1, ...overview.assigneeDistribution.map((item) => item.count))

  if (overview.assigneeDistribution.length === 0) {
    return <p className="analytics-list-empty">No active issues to distribute.</p>
  }

  return (
    <div className="analytics-bar-list analytics-assignee-list">
      {overview.assigneeDistribution.map((item) => (
        <DistributionBar
          key={assigneeKey(item)}
          label={item.displayName}
          detail={item.email ?? undefined}
          value={item.count}
          max={maxCount}
          color="var(--analytics-assignee)"
        />
      ))}
    </div>
  )
}

function DistributionBar({
  label,
  detail,
  value,
  max,
  color,
}: {
  label: string
  detail?: string
  value: number
  max: number
  color: string
}) {
  return (
    <div className="analytics-bar-row">
      <div className="analytics-bar-label">
        <span title={detail ? `${label} (${detail})` : label}>{label}</span>
        <strong>{formatNumber(value)}</strong>
      </div>
      <div className="analytics-bar-track" aria-hidden="true">
        <span
          style={{
            width: `${(value / max) * 100}%`,
            backgroundColor: color,
          }}
        />
      </div>
    </div>
  )
}

function AnalyticsLoadingState() {
  return (
    <div className="analytics-loading" aria-label="Loading analytics">
      <div className="analytics-metrics">
        {Array.from({ length: 4 }, (_, index) => (
          <span className="analytics-skeleton analytics-skeleton-metric" key={index} />
        ))}
      </div>
      <span className="analytics-skeleton analytics-skeleton-chart" />
    </div>
  )
}

function AnalyticsErrorState({ error }: { error: Error }) {
  return (
    <div className="analytics-error-state">
      <strong>Analytics could not be loaded.</strong>
      <p>{error.message || 'Try refreshing this page.'}</p>
    </div>
  )
}

function assigneeKey(item: AnalyticsAssigneeDistribution) {
  return item.userId ?? 'unassigned'
}

function formatNumber(value: number) {
  return new Intl.NumberFormat().format(value)
}

function formatPercent(value: number) {
  return new Intl.NumberFormat(undefined, {
    style: 'percent',
    maximumFractionDigits: 1,
  }).format(value)
}

function formatUtcDate(value: string, long = false) {
  return new Intl.DateTimeFormat(undefined, {
    month: long ? 'short' : 'numeric',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${value}T00:00:00Z`))
}
