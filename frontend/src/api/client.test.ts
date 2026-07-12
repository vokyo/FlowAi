import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  api,
  clearAccessTokenProvider,
  clearUnauthorizedHandler,
  setUnauthorizedHandler,
} from './client'
import { saveAuthTokens } from '@/auth/token-storage'

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api client', () => {
  beforeEach(() => {
    saveAuthTokens({ accessToken: 'old-access', refreshToken: 'refresh-token' })
    clearAccessTokenProvider()
    clearUnauthorizedHandler()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    clearAccessTokenProvider()
    clearUnauthorizedHandler()
  })

  it('shares one refresh request across concurrent unauthorized calls', async () => {
    let protectedAttempts = 0
    let refreshCalls = 0
    const authorizationHeaders: Array<string | null> = []
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/auth/refresh') {
        refreshCalls += 1
        expect(init?.body).toBe(JSON.stringify({ refreshToken: 'refresh-token' }))
        return jsonResponse({
          accessToken: 'new-access',
          refreshToken: 'new-refresh',
        })
      }

      protectedAttempts += 1
      authorizationHeaders.push(new Headers(init?.headers).get('Authorization'))
      if (protectedAttempts <= 2) {
        return jsonResponse({ message: 'Expired access token' }, 401)
      }

      return jsonResponse({ url })
    })
    vi.stubGlobal('fetch', fetchMock)

    const [projects, workspaces] = await Promise.all([
      api.get<{ url: string }>('/projects'),
      api.get<{ url: string }>('/workspaces'),
    ])

    expect(projects.url).toBe('/api/projects')
    expect(workspaces.url).toBe('/api/workspaces')
    expect(refreshCalls).toBe(1)
    expect(fetchMock).toHaveBeenCalledTimes(5)
    expect(authorizationHeaders.slice(0, 2)).toEqual([
      'Bearer old-access',
      'Bearer old-access',
    ])
    expect(authorizationHeaders.slice(2)).toEqual([
      'Bearer new-access',
      'Bearer new-access',
    ])
  })

  it('clears the session and invokes the unauthorized handler when refresh fails', async () => {
    const unauthorizedHandler = vi.fn()
    setUnauthorizedHandler(unauthorizedHandler)
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/refresh') {
        return jsonResponse({ message: 'Refresh token revoked' }, 401)
      }
      return jsonResponse({ message: 'Expired access token' }, 401)
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.get('/projects')).rejects.toMatchObject({ status: 401 })

    expect(window.localStorage.getItem('flowai.accessToken')).toBeNull()
    expect(window.localStorage.getItem('flowai.refreshToken')).toBeNull()
    expect(unauthorizedHandler).toHaveBeenCalledOnce()
  })
})
