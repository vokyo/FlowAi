import type { QueryClient } from '@tanstack/react-query'
import { clearAccessToken } from './access-token'

export function clearClientSession(queryClient: QueryClient) {
  clearAccessToken()
  void queryClient.cancelQueries()
  queryClient.clear()
}
