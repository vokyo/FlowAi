import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  api,
  refreshAccessToken,
  clearAccessTokenProvider,
  clearUnauthorizedHandler,
  setUnauthorizedHandler,
} from './client'
import {
  clearAccessToken,
  getAccessToken,
  setAccessToken,
} from '@/auth/access-token'

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api client', () => {
  beforeEach(() => {
    setAccessToken('old-access')
    clearAccessTokenProvider()
    clearUnauthorizedHandler()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    clearAccessToken()
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
        expect(init?.body).toBeUndefined()
        expect(init?.credentials).toBe('same-origin')
        return jsonResponse({
          accessToken: 'new-access',
        })
      }

      protectedAttempts += 1
      expect(init?.credentials).toBe('same-origin')
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

    expect(getAccessToken()).toBeNull()
    expect(unauthorizedHandler).toHaveBeenCalledOnce()
  })

  it('clears the session and invokes the unauthorized handler when refresh cannot be reached', async () => {
    const unauthorizedHandler = vi.fn()
    setUnauthorizedHandler(unauthorizedHandler)
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/refresh') {
        throw new TypeError('Network unavailable')
      }
      return jsonResponse({ message: 'Expired access token' }, 401)
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.get('/projects')).rejects.toMatchObject({ status: 401 })

    expect(getAccessToken()).toBeNull()
    expect(unauthorizedHandler).toHaveBeenCalledOnce()
  })

  it('restores an access token from the refresh cookie without a request body', async () => {
    clearAccessToken()
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.credentials).toBe('same-origin')
      expect(init?.body).toBeUndefined()
      return jsonResponse({ accessToken: 'restored-access' })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(refreshAccessToken()).resolves.toBe(true)

    expect(fetchMock).toHaveBeenCalledWith('/api/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
    })
    expect(getAccessToken()).toBe('restored-access')
  })
})
