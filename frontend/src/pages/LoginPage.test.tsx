import { MemoryRouter, Route, Routes } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { login } from '@/api/auth-api'
import { getDemoStatus } from '@/api/demo-api'
import { setAccessToken } from '@/auth/access-token'
import { LoginPage } from './LoginPage'

vi.mock('@/api/auth-api', () => ({
  login: vi.fn(),
}))

vi.mock('@/api/demo-api', () => ({
  getDemoStatus: vi.fn(),
}))

vi.mock('@/auth/access-token', () => ({
  setAccessToken: vi.fn(),
}))

function renderLogin(onAuthenticated = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/login?returnTo=/invite/invite-token']}>
        <Routes>
          <Route
            path="/login"
            element={(
              <LoginPage
                isAuthenticated={false}
                onAuthenticated={onAuthenticated}
              />
            )}
          />
          <Route path="/invite/:token" element={<h1>Invitation preview</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const authResponse = {
  accessToken: 'access-token',
  user: {
    id: 'user-1',
    email: 'user@example.com',
    displayName: 'Test User',
  },
  workspace: {
    id: 'workspace-1',
    name: 'Test Workspace',
    slug: 'test-workspace',
    role: 'OWNER',
  },
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getDemoStatus).mockResolvedValue({ enabled: false })
  })

  it('shows validation errors before calling the login API', async () => {
    const user = userEvent.setup()
    renderLogin()

    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Email is required.')).toBeInTheDocument()
    expect(screen.getByText('Password is required.')).toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()
  })

  it('keeps the access token in memory and returns to a safe invitation path after login', async () => {
    const user = userEvent.setup()
    const onAuthenticated = vi.fn()
    vi.mocked(login).mockResolvedValue(authResponse)
    renderLogin(onAuthenticated)

    await user.type(screen.getByLabelText('Email'), 'user@example.com')
    await user.type(screen.getByLabelText('Password'), 'password123')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Invitation preview' }))
      .toBeInTheDocument()
    expect(login).toHaveBeenCalledWith({
      email: 'user@example.com',
      password: 'password123',
    })
    expect(setAccessToken).toHaveBeenCalledWith('access-token')
    expect(onAuthenticated).toHaveBeenCalledOnce()
  })

  it('hides the demo entry point on a deployment that was never seeded', async () => {
    renderLogin()

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Explore the demo workspace/ }))
      .not.toBeInTheDocument()
  })

  it('signs into the seeded workspace with the credentials the server reports', async () => {
    const user = userEvent.setup()
    const onAuthenticated = vi.fn()
    vi.mocked(getDemoStatus).mockResolvedValue({
      enabled: true,
      email: 'demo@flowai.dev',
      password: 'demo1234',
    })
    vi.mocked(login).mockResolvedValue(authResponse)
    renderLogin(onAuthenticated)

    await user.click(
      await screen.findByRole('button', { name: /Explore the demo workspace/ }),
    )

    expect(await screen.findByRole('heading', { name: 'Invitation preview' }))
      .toBeInTheDocument()
    expect(login).toHaveBeenCalledWith({
      email: 'demo@flowai.dev',
      password: 'demo1234',
    })
    expect(setAccessToken).toHaveBeenCalledWith('access-token')
    expect(onAuthenticated).toHaveBeenCalledOnce()
  })

  it('leaves the form usable when the demo sign-in fails', async () => {
    const user = userEvent.setup()
    vi.mocked(getDemoStatus).mockResolvedValue({
      enabled: true,
      email: 'demo@flowai.dev',
      password: 'demo1234',
    })
    vi.mocked(login).mockRejectedValue(new Error('boom'))
    renderLogin()

    await user.click(
      await screen.findByRole('button', { name: /Explore the demo workspace/ }),
    )

    expect(await screen.findByText('Unable to open the demo workspace.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })
})
