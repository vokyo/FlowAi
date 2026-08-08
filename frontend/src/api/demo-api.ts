import { api } from '@/api/client'

/**
 * Whether this deployment carries the seeded demo workspace. The credentials
 * come back only when it does, so the sign-in page can offer a one-click way in
 * without the frontend hard-coding an account that may not exist.
 */
export type DemoStatus = {
  enabled: boolean
  email?: string | null
  password?: string | null
}

export function getDemoStatus() {
  return api.get<DemoStatus>('/demo/status')
}
