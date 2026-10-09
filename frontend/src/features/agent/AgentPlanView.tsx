import { useMemo } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { CalendarDays, UserRound } from 'lucide-react'
import type { ProjectPlan } from '@/api/agent-api'
import { getIssue, listProjectMembers } from '@/api/work-api'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import { queryKeys } from '@/lib/query-keys'
import { issuePath } from '@/routing/route-utils'
import { formatDateOnly } from '@/ui/display-utils'
import { PriorityBadge } from '@/ui/feature-ui'

type AgentPlanViewProps = {
  plan: ProjectPlan
  workspaceId: string
  projectId: string
}

/**
 * One version of a plan: the existing issues it reuses, then the new issues it would
 * create. The plan names people and issues by id, so their names are looked up
 * here, from the same caches the rest of the app fills.
 */
export function AgentPlanView({ plan, workspaceId, projectId }: AgentPlanViewProps) {
  const existingIssues = plan.existingIssues ?? []
  const membersQuery = useQuery({
    // Shared with the project members dialog, which reads the same list.
    queryKey: ['project-members', projectId],
    queryFn: () => listProjectMembers(projectId),
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
    retry: false,
  })
  const issueQueries = useQueries({
    queries: existingIssues.map((existing) => ({
      queryKey: queryKeys.issue(workspaceId, existing.issueId),
      queryFn: () => getIssue(existing.issueId),
      retry: false,
    })),
  })
  const memberNames = useMemo(
    () => new Map((membersQuery.data ?? []).map((member) => [member.userId, member.displayName])),
    [membersQuery.data],
  )

  return (
    <article className="agent-plan">
      {plan.overview ? <p className="agent-plan-overview">{plan.overview}</p> : null}

      {existingIssues.length > 0 ? (
        <section className="agent-plan-section">
          <h3>Already covered by existing issues</h3>
          <ul className="agent-existing-list">
            {existingIssues.map((existing, index) => {
              const issueQuery = issueQueries[index]
              return (
                <li key={existing.issueId}>
                  {issueQuery?.data ? (
                    <Link to={issuePath(workspaceId, projectId, existing.issueId)}>{issueQuery.data.title}</Link>
                  ) : (
                    <span className="agent-muted">
                      {issueQuery?.isLoading ? 'Loading issue…' : 'This issue is no longer available'}
                    </span>
                  )}
                  {existing.reason ? <p>{existing.reason}</p> : null}
                </li>
              )
            })}
          </ul>
        </section>
      ) : null}

      <section className="agent-plan-section">
        <h3>{plan.items.length === 0 ? 'No new issues' : `New issues to create (${plan.items.length})`}</h3>
        {plan.items.length === 0 ? (
          <p className="agent-muted">The existing issues above already cover the goal.</p>
        ) : (
          <ol className="agent-item-list">
            {plan.items.map((item) => (
              <li key={item.clientItemId} className="agent-item">
                <div className="agent-item-head">
                  <strong>{item.title}</strong>
                  <PriorityBadge priority={item.priority} />
                </div>
                {item.description ? <p className="agent-item-description">{item.description}</p> : null}
                <div className="agent-item-meta">
                  <span>
                    <UserRound aria-hidden="true" />
                    {item.suggestedAssigneeUserId
                      ? memberNames.get(item.suggestedAssigneeUserId) ?? 'A member who has left the project'
                      : 'Unassigned'}
                  </span>
                  <span>
                    <CalendarDays aria-hidden="true" />
                    {item.dueDate ? `Due ${formatDateOnly(item.dueDate)}` : 'No due date'}
                  </span>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>
    </article>
  )
}
