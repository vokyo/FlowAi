import type { ReactNode } from 'react'
import { ApiError } from '@/api/client'

// Shared by SettingsPage (account + workspace) and ProjectSettingsPage. The two
// pages are separate routes because their settings have different scopes, but
// they are the same surface visually and should stay that way.

export function MutationMessage({
  mutation,
  success,
}: {
  mutation: { error: Error | null; isSuccess: boolean; isPending: boolean }
  success?: string
}) {
  if (mutation.error) {
    return <p className="settings-message settings-message-error" role="alert">{errorMessage(mutation.error)}</p>
  }
  if (success && mutation.isSuccess && !mutation.isPending) {
    return <p className="settings-message settings-message-success" role="status">{success}</p>
  }
  return null
}

export function SettingsInlineState({ children }: { children: ReactNode }) {
  return <p className="settings-inline-state" role="status">{children}</p>
}

export function SettingsError({ error, compact = false }: { error: unknown; compact?: boolean }) {
  return (
    <div className={compact ? 'settings-message settings-message-error' : 'settings-page-state'} role="alert">
      <strong>Something went wrong.</strong>
      <p>{errorMessage(error)}</p>
    </div>
  )
}

export function SettingsSkeleton() {
  return (
    <main className="settings-page" aria-busy="true">
      <div className="settings-skeleton settings-skeleton-title" />
      <div className="settings-grid">
        <div className="settings-skeleton settings-skeleton-card" />
        <div className="settings-skeleton settings-skeleton-card" />
      </div>
    </main>
  )
}

function errorMessage(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : 'Please try again.'
}
