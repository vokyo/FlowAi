import { QueryClient } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getAccessToken, setAccessToken } from './access-token'
import { clearClientSession } from './client-session'

describe('clearClientSession', () => {
  beforeEach(() => {
    setAccessToken('access-token')
  })

  it('cancels active queries and removes all user-scoped cached data', () => {
    const queryClient = new QueryClient()
    const cancelQueries = vi.spyOn(queryClient, 'cancelQueries')
    queryClient.setQueryData(['current-session'], { user: { id: 'user-1' } })
    queryClient.setQueryData(['projects', 'workspace-1'], [{ id: 'project-1' }])

    clearClientSession(queryClient)

    expect(getAccessToken()).toBeNull()
    expect(cancelQueries).toHaveBeenCalledOnce()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  })
})
