import { describe, expect, it } from 'vitest'
import {
  invitationTokenFromReturnTo,
  safeAuthReturnTo,
} from './auth-navigation'

describe('auth navigation', () => {
  it('only accepts local invitation return paths', () => {
    expect(safeAuthReturnTo('/invite/token-123')).toBe('/invite/token-123')
    expect(safeAuthReturnTo('//attacker.example/invite/token')).toBe('/app')
    expect(safeAuthReturnTo('https://attacker.example')).toBe('/app')
    expect(safeAuthReturnTo('/app/workspaces/private')).toBe('/app')
    expect(safeAuthReturnTo(null)).toBe('/app')
  })

  it('extracts invitation tokens without accepting other routes', () => {
    expect(invitationTokenFromReturnTo('/invite/token-123')).toBe('token-123')
    expect(invitationTokenFromReturnTo('/app')).toBeNull()
  })
})
