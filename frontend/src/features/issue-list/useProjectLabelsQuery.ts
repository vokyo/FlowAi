import { useQuery } from '@tanstack/react-query'
import { listProjectLabels, type ProjectLabel } from '@/work/work-api'

const EMPTY_LABELS: ProjectLabel[] = []

export function useProjectLabelsQuery(projectId: string | null, enabled: boolean) {
  const query = useQuery({
    queryKey: ['project-labels', projectId],
    queryFn: () => listProjectLabels(projectId ?? ''),
    enabled,
    retry: false,
  })
  return { query, labels: query.data ?? EMPTY_LABELS }
}
