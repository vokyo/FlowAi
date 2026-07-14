import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearAccessToken,
  getAccessToken,
  hasAccessToken,
  setAccessToken,
} from './access-token'

describe('access token memory', () => {
  beforeEach(() => {
    clearAccessToken()
    window.localStorage.clear()
  })

  it('never persists an access token to localStorage', () => {
    setAccessToken('access-token')

    expect(getAccessToken()).toBe('access-token')
    expect(hasAccessToken()).toBe(true)
    expect(window.localStorage.getItem('flowai.accessToken')).toBeNull()
    expect(window.localStorage.getItem('flowai.refreshToken')).toBeNull()
  })

  it('clears the in-memory token', () => {
    setAccessToken('access-token')

    clearAccessToken()

    expect(getAccessToken()).toBeNull()
    expect(hasAccessToken()).toBe(false)
  })
})
