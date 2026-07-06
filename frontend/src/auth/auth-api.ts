import { api } from '@/api/client'

export type AuthUser = {
  id: string
  email: string
  displayName: string
}

export type AuthWorkspace = {
  id: string
  name: string
  slug: string
  role?: string
}

export type AuthResponse = {
  accessToken: string
  refreshToken: string
  user: AuthUser
  workspace?: AuthWorkspace
}

export type LoginRequest = {
  email: string
  password: string
}

export type RegisterRequest = {
  email: string
  password: string
  displayName: string
  workspaceName: string
}

export type CurrentSessionResponse = {
  user: AuthUser
  workspace: AuthWorkspace
}

export function login(request: LoginRequest) {
  return api.post<AuthResponse>('/auth/login', request, { auth: false })
}

export function register(request: RegisterRequest) {
  return api.post<AuthResponse>('/auth/register', request, { auth: false })
}

export function getCurrentSession() {
  return api.get<CurrentSessionResponse>('/me')
}
