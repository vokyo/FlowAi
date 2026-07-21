import { useQuery } from '@tanstack/react-query'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import { listProjectLabels, type ProjectLabel } from '@/work/work-api'

const EMPTY_LABELS: ProjectLabel[] = []

export function useProjectLabelsQuery(projectId: string | null, enabled: boolean) {
  const query = useQuery({
    queryKey: ['project-labels', projectId],
    queryFn: () => listProjectLabels(projectId ?? ''),
    enabled,
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
    retry: false,
  })
  return { query, labels: query.data ?? EMPTY_LABELS }
}
