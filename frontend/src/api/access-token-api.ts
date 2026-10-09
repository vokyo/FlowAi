import { api } from '@/api/client'

/** A personal access token as listed: the token itself is never sent again. */
export type AccessToken = {
  id: string
  name: string
  createdAt: string
  expiresAt: string
  lastUsedAt: string | null
  expired: boolean
}

/** The one response that carries the token, right after it is created. */
export type CreatedAccessToken = {
  id: string
  name: string
  token: string
  createdAt: string
  expiresAt: string
}

export const ACCESS_TOKEN_LIFETIMES = [30, 90, 365] as const
export type AccessTokenLifetime = (typeof ACCESS_TOKEN_LIFETIMES)[number]

export function listAccessTokens() {
  return api.get<AccessToken[]>('/me/access-tokens')
}

export function createAccessToken(request: { name: string; lifetimeDays: AccessTokenLifetime }) {
  return api.post<CreatedAccessToken>('/me/access-tokens', request)
}

export function revokeAccessToken(id: string) {
  return api.delete<void>(`/me/access-tokens/${id}`)
}
