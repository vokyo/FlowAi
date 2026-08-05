import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ChevronRight, FolderKanban, KeyRound, Save, Shield, Trash2, UserRound } from 'lucide-react'
import { useNavigate } from 'react-router'
import { changePassword, getCurrentSession, revokeAllSessions, updateProfile } from '@/api/auth-api'
import { clearClientSession } from '@/auth/client-session'
import { Button } from '@/components/ui/button'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import { projectSettingsPath } from '@/routing/route-utils'
import { listProjects, listWorkspaceMembers, type Project, type WorkspaceMember } from '@/api/work-api'
import { removeWorkspaceMember, updateWorkspaceMember, type WorkspaceRole } from '@/api/workspace-api'
import {
  MutationMessage,
  SettingsError,
  SettingsInlineState,
  SettingsSkeleton,
} from '@/pages/SettingsShared'

/**
 * Settings whose scope is the signed-in user or the whole workspace. Anything
 * scoped to a single project lives at that project's own settings route — see
 * ProjectSettingsPage — so this page never has to ask which project you meant.
 */
export function SettingsPage({ onSessionChanged }: { onSessionChanged: () => void }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const sessionQuery = useQuery({ queryKey: ['current-session'], queryFn: getCurrentSession })
  const projectsQuery = useQuery({
    queryKey: ['projects', sessionQuery.data?.workspace.id],
    queryFn: () => listProjects(true),
    enabled: sessionQuery.isSuccess,
  })
  const membersQuery = useQuery({
    queryKey: ['workspace-members', sessionQuery.data?.workspace.id],
    queryFn: listWorkspaceMembers,
    enabled: sessionQuery.isSuccess,
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
  })

  function finishSensitiveAction() {
    clearClientSession(queryClient)
    onSessionChanged()
    navigate('/login', { replace: true })
  }

  if (sessionQuery.isLoading) {
    return <SettingsSkeleton />
  }
  if (sessionQuery.error || !sessionQuery.data) {
    return <SettingsError error={sessionQuery.error} />
  }

  const { user, workspace } = sessionQuery.data

  return (
    <main className="settings-page">
      <header className="settings-header">
        <Button type="button" variant="ghost" onClick={() => navigate('/app')}>
          <ArrowLeft aria-hidden="true" /> Back to workspace
        </Button>
        <div>
          <p className="settings-eyebrow">{workspace.name}</p>
          <h1>Settings</h1>
          <p>Manage your account and workspace access.</p>
        </div>
      </header>

      <div className="settings-grid">
        <AccountSettings
          displayName={user.displayName}
          onProfileSaved={async () => {
            await queryClient.invalidateQueries({ queryKey: ['current-session'] })
          }}
          onSignedOut={finishSensitiveAction}
        />
        <WorkspaceMemberSettings
          currentUserId={user.id}
          currentRole={workspace.role as WorkspaceRole}
          members={membersQuery.data ?? []}
          isLoading={membersQuery.isLoading}
          error={membersQuery.error}
          onChanged={() => queryClient.invalidateQueries({ queryKey: ['workspace-members'] })}
        />
        <ProjectSettingsLinks
          workspaceId={workspace.id}
          projects={projectsQuery.data ?? []}
          isLoading={projectsQuery.isLoading}
          error={projectsQuery.error}
          onOpen={(projectId) => navigate(projectSettingsPath(workspace.id, projectId))}
        />
      </div>
    </main>
  )
}

/**
 * Project settings moved out of this page, so anyone arriving here out of habit
 * gets a signpost rather than a dead end.
 */
function ProjectSettingsLinks({ workspaceId, projects, isLoading, error, onOpen }: {
  workspaceId: string
  projects: Project[]
  isLoading: boolean
  error: Error | null
  onOpen: (projectId: string) => void
}) {
  return (
    <section className="settings-card settings-card-wide" aria-labelledby="project-links-title">
      <div className="settings-card-heading">
        <FolderKanban aria-hidden="true" />
        <div>
          <h2 id="project-links-title">Projects</h2>
          <p>Each project keeps its own settings — name, labels, workflow states, and removal.</p>
        </div>
      </div>
      {isLoading ? <SettingsInlineState>Loading projects…</SettingsInlineState> : null}
      {error ? <SettingsError error={error} compact /> : null}
      {!isLoading && !error && projects.length === 0 ? (
        <SettingsInlineState>No projects yet. Create one from the workspace.</SettingsInlineState>
      ) : null}
      <div className="settings-list">
        {projects.map((project) => (
          <div className="settings-list-row" key={project.id}>
            <div>
              <strong>{project.name}</strong>
              {project.archivedAt ? <small>Archived</small> : null}
            </div>
            <div className="settings-row-actions">
              <Button
                size="sm"
                variant="outline"
                onClick={() => onOpen(project.id)}
                aria-label={`Open settings for ${project.name}`}
              >
                Settings <ChevronRight aria-hidden="true" />
              </Button>
            </div>
          </div>
        ))}
      </div>
      <p className="settings-hint">
        Or open a project in the sidebar and use its <strong>⋯</strong> menu.
        Direct link: <code>{`${projectSettingsPath(workspaceId, ':projectId')}`}</code>
      </p>
    </section>
  )
}

function AccountSettings({
  displayName,
  onProfileSaved,
  onSignedOut,
}: {
  displayName: string
  onProfileSaved: () => Promise<void>
  onSignedOut: () => void
}) {
  const [name, setName] = useState(displayName)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const profileMutation = useMutation({
    mutationFn: () => updateProfile({ displayName: name.trim() }),
    onSuccess: onProfileSaved,
  })
  const passwordMutation = useMutation({
    mutationFn: () => changePassword({ currentPassword, newPassword }),
    onSuccess: onSignedOut,
  })
  const sessionsMutation = useMutation({ mutationFn: revokeAllSessions, onSuccess: onSignedOut })

  return (
    <section className="settings-card" aria-labelledby="account-settings-title">
      <div className="settings-card-heading">
        <UserRound aria-hidden="true" />
        <div><h2 id="account-settings-title">Account</h2><p>Update your profile and credentials.</p></div>
      </div>
      <form className="settings-form" onSubmit={(event) => { event.preventDefault(); profileMutation.mutate() }}>
        <label className="settings-field"><span>Display name</span><input value={name} maxLength={120} required onChange={(event) => setName(event.target.value)} /></label>
        <MutationMessage mutation={profileMutation} success="Profile saved." />
        <Button type="submit" disabled={profileMutation.isPending || !name.trim()}><Save aria-hidden="true" /> Save profile</Button>
      </form>
      <hr />
      <form className="settings-form" onSubmit={(event) => { event.preventDefault(); passwordMutation.mutate() }}>
        <label className="settings-field"><span>Current password</span><input type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} /></label>
        <label className="settings-field"><span>New password</span><input type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={newPassword} onChange={(event) => setNewPassword(event.target.value)} /></label>
        <MutationMessage mutation={passwordMutation} />
        <Button type="submit" variant="outline" disabled={passwordMutation.isPending}><KeyRound aria-hidden="true" /> Change password</Button>
      </form>
      <Button type="button" variant="ghost" className="settings-danger-text" disabled={sessionsMutation.isPending} onClick={() => {
        if (window.confirm('Sign out every active session?')) sessionsMutation.mutate()
      }}>Revoke all sessions</Button>
      <MutationMessage mutation={sessionsMutation} />
    </section>
  )
}

function WorkspaceMemberSettings({ currentUserId, currentRole, members, isLoading, error, onChanged }: {
  currentUserId: string
  currentRole: WorkspaceRole
  members: WorkspaceMember[]
  isLoading: boolean
  error: Error | null
  onChanged: () => Promise<unknown>
}) {
  const mutation = useMutation({
    mutationFn: ({ id, role, status }: { id: string; role?: WorkspaceRole; status?: 'ACTIVE' | 'DISABLED' }) => updateWorkspaceMember(id, { role, status }),
    onSuccess: onChanged,
  })
  const removeMutation = useMutation({ mutationFn: removeWorkspaceMember, onSuccess: onChanged })
  const canManage = currentRole === 'OWNER' || currentRole === 'ADMIN'

  return (
    <section className="settings-card" aria-labelledby="member-settings-title">
      <div className="settings-card-heading"><Shield aria-hidden="true" /><div><h2 id="member-settings-title">Workspace members</h2><p>Change roles or suspend access.</p></div></div>
      {isLoading ? <SettingsInlineState>Loading members…</SettingsInlineState> : null}
      {error ? <SettingsError error={error} compact /> : null}
      <div className="settings-list">
        {members.map((member) => {
          const isSelf = member.userId === currentUserId
          const protectedMember = isSelf || member.role === 'OWNER'
          const editable = canManage && !protectedMember && !(currentRole === 'ADMIN' && member.role === 'ADMIN')
          return (
            <div className="settings-list-row" key={member.id}>
              <div><strong>{member.displayName}</strong><small>{member.email} · {member.status.toLowerCase()}</small></div>
              <div className="settings-row-actions">
                {editable ? (
                  <select aria-label={`Role for ${member.displayName}`} value={member.role} disabled={mutation.isPending} onChange={(event) => mutation.mutate({ id: member.id, role: event.target.value as WorkspaceRole })}>
                    <option value="ADMIN">Admin</option><option value="MEMBER">Member</option><option value="GUEST">Guest</option>
                  </select>
                ) : (
                  // A select that can never open reads as broken — and for these
                  // rows it never can: you cannot demote yourself out of your own
                  // workspace, and the owner role has nowhere to go. Say which.
                  <span className="settings-static-role">
                    {titleCaseRole(member.role)}
                    <small>{lockedRoleReason(isSelf, member.role, canManage)}</small>
                  </span>
                )}
                {editable && member.status === 'DISABLED' ? <Button size="sm" variant="outline" onClick={() => mutation.mutate({ id: member.id, status: 'ACTIVE' })}>Reactivate</Button> : null}
                {editable && member.status === 'ACTIVE' ? <Button size="icon-xs" variant="ghost" aria-label={`Remove ${member.displayName}`} onClick={() => { if (window.confirm(`Remove ${member.displayName} from this workspace?`)) removeMutation.mutate(member.id) }}><Trash2 aria-hidden="true" /></Button> : null}
              </div>
            </div>
          )
        })}
      </div>
      {members.length === 1 ? (
        <SettingsInlineState>You are the only member. Invite someone from the workspace switcher to start assigning roles.</SettingsInlineState>
      ) : null}
      <MutationMessage mutation={mutation} />
      <MutationMessage mutation={removeMutation} />
    </section>
  )
}

function titleCaseRole(role: string) {
  return role.charAt(0) + role.slice(1).toLowerCase()
}

/** Why this row's role is fixed. Ordered by which rule actually bites first. */
function lockedRoleReason(isSelf: boolean, role: string, canManage: boolean) {
  if (isSelf) return 'Your own role'
  if (role === 'OWNER') return 'Workspace owner'
  return canManage ? 'Managed by the owner' : 'Requires admin access'
}
