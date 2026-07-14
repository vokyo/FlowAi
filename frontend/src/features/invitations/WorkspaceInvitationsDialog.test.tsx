import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WorkspaceInvitationsDialog } from './WorkspaceInvitationsDialog'

describe('WorkspaceInvitationsDialog history pagination', () => {
  afterEach(() => vi.restoreAllMocks())

  it('keeps history actions available while loading additional pages', async () => {
    const onReissue = vi.fn()
    const onRevoke = vi.fn()
    const onLoadMore = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    render(
      <WorkspaceInvitationsDialog
        isOpen
        currentWorkspace={{ id: 'workspace-1', name: 'Workspace', slug: 'workspace', role: 'OWNER' }}
        invitations={[{
          id: 'invitation-1',
          email: 'member@example.com',
          role: 'MEMBER',
          status: 'PENDING',
          expiresAt: '2026-08-01T00:00:00Z',
          createdAt: '2026-07-14T00:00:00Z',
        }]}
        latestInvitationLink={null}
        isLoading={false}
        hasMore
        isLoadingMore={false}
        isMutating={false}
        error={null}
        onSubmit={vi.fn(async () => undefined)}
        onReissue={onReissue}
        onRevoke={onRevoke}
        onLoadMore={onLoadMore}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByText('member@example.com')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Reissue invitation for member@example.com' }))
    await userEvent.click(screen.getByRole('button', { name: 'Revoke invitation for member@example.com' }))
    await userEvent.click(screen.getByRole('button', { name: 'Load more invitations' }))

    expect(onReissue).toHaveBeenCalledWith('invitation-1')
    expect(onRevoke).toHaveBeenCalledWith('invitation-1')
    expect(onLoadMore).toHaveBeenCalledOnce()
  })
})
