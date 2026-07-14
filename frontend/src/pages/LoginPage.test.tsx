import { MemoryRouter, Route, Routes } from 'react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { login } from '@/auth/auth-api'
import { setAccessToken } from '@/auth/access-token'
import { LoginPage } from './LoginPage'

vi.mock('@/auth/auth-api', () => ({
  login: vi.fn(),
}))

vi.mock('@/auth/access-token', () => ({
  setAccessToken: vi.fn(),
}))

function renderLogin(onAuthenticated = vi.fn()) {
  render(
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
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows validation errors before calling the login API', async () => {
    const user = userEvent.setup()
    renderLogin()

    await user.clear(screen.getByLabelText('Email'))
    await user.clear(screen.getByLabelText('Password'))
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Email is required.')).toBeInTheDocument()
    expect(screen.getByText('Password is required.')).toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()
  })

  it('keeps the access token in memory and returns to a safe invitation path after login', async () => {
    const user = userEvent.setup()
    const onAuthenticated = vi.fn()
    const response = {
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
    vi.mocked(login).mockResolvedValue(response)
    renderLogin(onAuthenticated)

    await user.clear(screen.getByLabelText('Email'))
    await user.type(screen.getByLabelText('Email'), 'user@example.com')
    await user.clear(screen.getByLabelText('Password'))
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
})
