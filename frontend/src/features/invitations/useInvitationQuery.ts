import { useMemo } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { mergeCursorPageItems } from '@/api/pagination'
import { listWorkspaceInvitations } from '@/workspace/workspace-api'

export function useInvitationQuery({
  workspaceId,
  enabled,
}: {
  workspaceId: string | null
  enabled: boolean
}) {
  const query = useInfiniteQuery({
    queryKey: ['workspace-invitations', workspaceId],
    queryFn: ({ pageParam }) => listWorkspaceInvitations(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled,
    retry: false,
  })
  const invitations = useMemo(
    () => mergeCursorPageItems(query.data?.pages),
    [query.data?.pages],
  )
  return { query, invitations }
}
