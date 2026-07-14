import { useInfiniteQuery } from '@tanstack/react-query'
import type { CursorPage } from '@/api/pagination'
import {
  getBoardColumnPage,
  type BoardColumn,
  type IssueSummary,
} from '@/work/work-api'

export function useBoardColumnPagination({
  column,
  projectId,
  workspaceId,
  onPageLoaded,
}: {
  column: BoardColumn
  projectId: string
  workspaceId: string
  onPageLoaded: (workflowStateId: string, page: CursorPage<IssueSummary>) => void
}) {
  const query = useInfiniteQuery({
    queryKey: ['project-board-column', workspaceId, projectId, column.workflowState.id],
    queryFn: async ({ pageParam }) => {
      const page = await getBoardColumnPage(projectId, column.workflowState.id, pageParam)
      onPageLoaded(column.workflowState.id, page)
      return page
    },
    initialPageParam: column.nextCursor,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: false,
    retry: false,
  })
  const loadedPages = query.data?.pages
  const nextCursor = loadedPages?.length
    ? loadedPages.at(-1)?.nextCursor ?? null
    : column.nextCursor
  return { query, displayedIssues: column.issues, nextCursor }
}
