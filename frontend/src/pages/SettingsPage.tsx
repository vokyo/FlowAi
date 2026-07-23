import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, KeyRound, Save, Shield, Tag, Trash2, UserRound, Workflow } from 'lucide-react'
import { useNavigate } from 'react-router'
import { ApiError } from '@/api/client'
import { changePassword, getCurrentSession, revokeAllSessions, updateProfile } from '@/auth/auth-api'
import { clearClientSession } from '@/auth/client-session'
import { Button } from '@/components/ui/button'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import {
  archiveProject,
  createProjectLabel,
  createProjectWorkflowState,
  deleteProject,
  deleteProjectLabel,
  deleteProjectWorkflowState,
  listProjectLabels,
  listProjectWorkflowStates,
  listProjects,
  listWorkspaceMembers,
  restoreProject,
  updateProject,
  updateProjectLabel,
  updateProjectWorkflowState,
  type ProjectLabel,
  type ProjectWorkflowState,
  type WorkflowStateCategory,
} from '@/work/work-api'
import { removeWorkspaceMember, updateWorkspaceMember, type WorkspaceRole } from '@/workspace/workspace-api'

const categories: WorkflowStateCategory[] = ['TODO', 'IN_PROGRESS', 'DONE']

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
  const [selectedProjectId, setSelectedProjectId] = useState('')

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
  const effectiveSelectedProjectId = selectedProjectId || projectsQuery.data?.[0]?.id || ''
  const selectedProject = projectsQuery.data?.find((project) => project.id === effectiveSelectedProjectId)

  return (
    <main className="settings-page">
      <header className="settings-header">
        <Button type="button" variant="ghost" onClick={() => navigate('/app')}>
          <ArrowLeft aria-hidden="true" /> Back to workspace
        </Button>
        <div>
          <p className="settings-eyebrow">{workspace.name}</p>
          <h1>Settings</h1>
          <p>Manage your account, workspace access, projects, labels, and workflow states.</p>
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
        <section className="settings-card settings-card-wide" aria-labelledby="project-settings-title">
          <div className="settings-card-heading">
            <Shield aria-hidden="true" />
            <div>
              <h2 id="project-settings-title">Project administration</h2>
              <p>Owner-only changes apply immediately to every project member.</p>
            </div>
          </div>
          {projectsQuery.isLoading ? <SettingsInlineState>Loading projects…</SettingsInlineState> : null}
          {projectsQuery.error ? <SettingsError error={projectsQuery.error} compact /> : null}
          {projectsQuery.data?.length === 0 ? (
            <SettingsInlineState>No projects yet. Create one from the workspace.</SettingsInlineState>
          ) : null}
          {projectsQuery.data?.length ? (
            <>
              <label className="settings-field">
                <span>Project</span>
                <select value={effectiveSelectedProjectId} onChange={(event) => setSelectedProjectId(event.target.value)}>
                  {projectsQuery.data.map((project) => <option key={project.id} value={project.id}>{project.name}{project.archivedAt ? ' (archived)' : ''}</option>)}
                </select>
              </label>
              {selectedProject ? (
                <ProjectSettings
                  key={selectedProject.id}
                  project={selectedProject}
                  workspaceId={workspace.id}
                  onProjectChanged={async () => {
                    await queryClient.invalidateQueries({ queryKey: ['projects'] })
                  }}
                  onProjectRemoved={async () => {
                    setSelectedProjectId('')
                    await queryClient.invalidateQueries({ queryKey: ['projects'] })
                  }}
                />
              ) : null}
            </>
          ) : null}
        </section>
      </div>
    </main>
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
  members: import('@/work/work-api').WorkspaceMember[]
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
          const protectedMember = member.userId === currentUserId || member.role === 'OWNER'
          const editable = canManage && !protectedMember && !(currentRole === 'ADMIN' && member.role === 'ADMIN')
          return (
            <div className="settings-list-row" key={member.id}>
              <div><strong>{member.displayName}</strong><small>{member.email} · {member.status.toLowerCase()}</small></div>
              <div className="settings-row-actions">
                <select aria-label={`Role for ${member.displayName}`} value={member.role} disabled={!editable || mutation.isPending} onChange={(event) => mutation.mutate({ id: member.id, role: event.target.value as WorkspaceRole })}>
                  {member.role === 'OWNER' ? <option value="OWNER">Owner</option> : null}
                  <option value="ADMIN">Admin</option><option value="MEMBER">Member</option><option value="GUEST">Guest</option>
                </select>
                {editable && member.status === 'DISABLED' ? <Button size="sm" variant="outline" onClick={() => mutation.mutate({ id: member.id, status: 'ACTIVE' })}>Reactivate</Button> : null}
                {editable && member.status === 'ACTIVE' ? <Button size="icon-xs" variant="ghost" aria-label={`Remove ${member.displayName}`} onClick={() => { if (window.confirm(`Remove ${member.displayName} from this workspace?`)) removeMutation.mutate(member.id) }}><Trash2 aria-hidden="true" /></Button> : null}
              </div>
            </div>
          )
        })}
      </div>
      <MutationMessage mutation={mutation} />
      <MutationMessage mutation={removeMutation} />
    </section>
  )
}

function ProjectSettings({ project, workspaceId, onProjectChanged, onProjectRemoved }: {
  project: import('@/work/work-api').Project
  workspaceId: string
  onProjectChanged: () => Promise<void>
  onProjectRemoved: () => Promise<void>
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(project.name)
  const [description, setDescription] = useState(project.description ?? '')
  const labelsQuery = useQuery({
    queryKey: ['project-labels', project.id],
    queryFn: () => listProjectLabels(project.id),
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
  })
  const statesQuery = useQuery({
    queryKey: ['project-workflow-states', project.id],
    queryFn: () => listProjectWorkflowStates(project.id),
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
  })
  const updateMutation = useMutation({ mutationFn: () => updateProject(project.id, { name: name.trim(), description: description.trim() }), onSuccess: onProjectChanged })
  const archiveMutation = useMutation({ mutationFn: () => archiveProject(project.id), onSuccess: onProjectChanged })
  const restoreMutation = useMutation({ mutationFn: () => restoreProject(project.id), onSuccess: onProjectChanged })
  const deleteMutation = useMutation({ mutationFn: () => deleteProject(project.id), onSuccess: onProjectRemoved })
  const invalidateLabels = () => queryClient.invalidateQueries({ queryKey: ['project-labels', project.id] })
  const invalidateStates = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['project-workflow-states', project.id] }),
      queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, project.id] }),
      queryClient.invalidateQueries({ queryKey: ['project-board', workspaceId, project.id] }),
    ])
  }

  return (
    <div className="project-settings-sections">
      <form className="settings-form" onSubmit={(event) => { event.preventDefault(); updateMutation.mutate() }}>
        <label className="settings-field"><span>Name</span><input required maxLength={160} value={name} onChange={(event) => setName(event.target.value)} /></label>
        <label className="settings-field"><span>Description</span><textarea maxLength={5000} rows={3} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
        <MutationMessage mutation={updateMutation} success="Project saved." />
        <Button type="submit" disabled={updateMutation.isPending || !name.trim()}><Save aria-hidden="true" /> Save project</Button>
      </form>
      <LabelSettings projectId={project.id} labels={labelsQuery.data ?? []} isLoading={labelsQuery.isLoading} onChanged={invalidateLabels} />
      <WorkflowSettings projectId={project.id} states={statesQuery.data ?? []} isLoading={statesQuery.isLoading} onChanged={invalidateStates} />
      <div className="settings-danger-zone">
        <h3>Danger zone</h3>
        <p>Archiving hides this project from the workspace. Deleting permanently removes its issues, comments, labels, and activity.</p>
        <div>{project.archivedAt ? <Button variant="outline" disabled={restoreMutation.isPending} onClick={() => restoreMutation.mutate()}>Restore project</Button> : <Button variant="outline" disabled={archiveMutation.isPending} onClick={() => { if (window.confirm(`Archive ${project.name}?`)) archiveMutation.mutate() }}>Archive project</Button>}<Button variant="destructive" disabled={deleteMutation.isPending} onClick={() => { if (window.confirm(`Permanently delete ${project.name}? This cannot be undone.`)) deleteMutation.mutate() }}><Trash2 aria-hidden="true" /> Delete project</Button></div>
        <MutationMessage mutation={archiveMutation} /><MutationMessage mutation={restoreMutation} /><MutationMessage mutation={deleteMutation} />
      </div>
    </div>
  )
}

function LabelSettings({ projectId, labels, isLoading, onChanged }: { projectId: string; labels: ProjectLabel[]; isLoading: boolean; onChanged: () => Promise<unknown> }) {
  const [newName, setNewName] = useState('')
  const [newColor, setNewColor] = useState('#64748b')
  const createMutation = useMutation({ mutationFn: () => createProjectLabel(projectId, { name: newName.trim(), color: newColor }), onSuccess: async () => { setNewName(''); await onChanged() } })
  const updateMutation = useMutation({ mutationFn: ({ id, name, color }: { id: string; name: string; color: string }) => updateProjectLabel(projectId, id, { name, color }), onSuccess: onChanged })
  const deleteMutation = useMutation({ mutationFn: (id: string) => deleteProjectLabel(projectId, id), onSuccess: onChanged })
  return (
    <section className="settings-subsection"><div className="settings-card-heading"><Tag aria-hidden="true" /><div><h3>Labels</h3><p>Edit or remove project labels.</p></div></div>
      {isLoading ? <SettingsInlineState>Loading labels…</SettingsInlineState> : null}
      <div className="settings-list">{labels.map((label) => <EditableLabel key={label.id} label={label} isPending={updateMutation.isPending || deleteMutation.isPending} onSave={(name, color) => updateMutation.mutate({ id: label.id, name, color })} onDelete={() => deleteMutation.mutate(label.id)} />)}</div>
      <form className="settings-inline-form" onSubmit={(event) => { event.preventDefault(); createMutation.mutate() }}><input aria-label="New label color" type="color" value={newColor} onChange={(event) => setNewColor(event.target.value)} /><input aria-label="New label name" placeholder="New label" maxLength={60} required value={newName} onChange={(event) => setNewName(event.target.value)} /><Button size="sm" disabled={createMutation.isPending || !newName.trim()}>Add label</Button></form>
      <MutationMessage mutation={createMutation} /><MutationMessage mutation={updateMutation} /><MutationMessage mutation={deleteMutation} />
    </section>
  )
}

function EditableLabel({ label, isPending, onSave, onDelete }: { label: ProjectLabel; isPending: boolean; onSave: (name: string, color: string) => void; onDelete: () => void }) {
  const [name, setName] = useState(label.name); const [color, setColor] = useState(label.color)
  return <div className="settings-list-row settings-edit-row"><input aria-label={`Color for ${label.name}`} type="color" value={color} onChange={(event) => setColor(event.target.value)} /><input aria-label={`Name for ${label.name}`} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} /><Button size="icon-xs" variant="ghost" disabled={isPending || !name.trim()} aria-label={`Save ${label.name}`} onClick={() => onSave(name.trim(), color)}><Save aria-hidden="true" /></Button><Button size="icon-xs" variant="ghost" disabled={isPending} aria-label={`Delete ${label.name}`} onClick={() => { if (window.confirm(`Delete label ${label.name}? It will be removed from every issue.`)) onDelete() }}><Trash2 aria-hidden="true" /></Button></div>
}

function WorkflowSettings({ projectId, states, isLoading, onChanged }: { projectId: string; states: ProjectWorkflowState[]; isLoading: boolean; onChanged: () => Promise<unknown> }) {
  const [newName, setNewName] = useState(''); const [newCategory, setNewCategory] = useState<WorkflowStateCategory>('TODO')
  const createMutation = useMutation({ mutationFn: () => createProjectWorkflowState(projectId, { name: newName.trim(), category: newCategory }), onSuccess: async () => { setNewName(''); await onChanged() } })
  const updateMutation = useMutation({ mutationFn: ({ id, name, category }: { id: string; name: string; category: WorkflowStateCategory }) => updateProjectWorkflowState(projectId, id, { name, category }), onSuccess: onChanged })
  const deleteMutation = useMutation({ mutationFn: ({ id, replacementId }: { id: string; replacementId: string }) => deleteProjectWorkflowState(projectId, id, replacementId), onSuccess: onChanged })
  return <section className="settings-subsection"><div className="settings-card-heading"><Workflow aria-hidden="true" /><div><h3>Workflow states</h3><p>Deleting a state requires moving its issues to another state.</p></div></div>
    {isLoading ? <SettingsInlineState>Loading workflow states…</SettingsInlineState> : null}
    <div className="settings-list">{states.map((state) => <EditableWorkflowState key={state.id} state={state} states={states} isPending={updateMutation.isPending || deleteMutation.isPending} onSave={(name, category) => updateMutation.mutate({ id: state.id, name, category })} onDelete={(replacementId) => deleteMutation.mutate({ id: state.id, replacementId })} />)}</div>
    <form className="settings-inline-form" onSubmit={(event) => { event.preventDefault(); createMutation.mutate() }}><input aria-label="New workflow state name" placeholder="New state" maxLength={60} required value={newName} onChange={(event) => setNewName(event.target.value)} /><select aria-label="New workflow category" value={newCategory} onChange={(event) => setNewCategory(event.target.value)}>{categories.map((category) => <option key={category} value={category}>{category.replace('_', ' ')}</option>)}</select><Button size="sm" disabled={createMutation.isPending || !newName.trim()}>Add state</Button></form>
    <MutationMessage mutation={createMutation} /><MutationMessage mutation={updateMutation} /><MutationMessage mutation={deleteMutation} />
  </section>
}

function EditableWorkflowState({ state, states, isPending, onSave, onDelete }: { state: ProjectWorkflowState; states: ProjectWorkflowState[]; isPending: boolean; onSave: (name: string, category: WorkflowStateCategory) => void; onDelete: (replacementId: string) => void }) {
  const [name, setName] = useState(state.name); const [category, setCategory] = useState(state.category); const replacements = states.filter((item) => item.id !== state.id); const [replacementId, setReplacementId] = useState(replacements[0]?.id ?? '')
  return <div className="settings-workflow-row"><div className="settings-edit-row"><input aria-label={`Name for ${state.name}`} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} /><select aria-label={`Category for ${state.name}`} value={category} onChange={(event) => setCategory(event.target.value)}>{categories.map((item) => <option key={item} value={item}>{item.replace('_', ' ')}</option>)}</select><Button size="icon-xs" variant="ghost" disabled={isPending || !name.trim()} aria-label={`Save ${state.name}`} onClick={() => onSave(name.trim(), category)}><Save aria-hidden="true" /></Button></div><div className="settings-migration-action"><span>Move issues to</span><select aria-label={`Replacement for ${state.name}`} value={replacementId} disabled={replacements.length === 0} onChange={(event) => setReplacementId(event.target.value)}>{replacements.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select><Button size="icon-xs" variant="ghost" disabled={isPending || !replacementId} aria-label={`Delete ${state.name}`} onClick={() => { if (window.confirm(`Delete ${state.name} and move its issues?`)) onDelete(replacementId) }}><Trash2 aria-hidden="true" /></Button></div></div>
}

function MutationMessage({ mutation, success }: { mutation: { error: Error | null; isSuccess: boolean; isPending: boolean }; success?: string }) {
  if (mutation.error) return <p className="settings-message settings-message-error" role="alert">{errorMessage(mutation.error)}</p>
  if (success && mutation.isSuccess && !mutation.isPending) return <p className="settings-message settings-message-success" role="status">{success}</p>
  return null
}

function SettingsInlineState({ children }: { children: React.ReactNode }) { return <p className="settings-inline-state" role="status">{children}</p> }
function SettingsError({ error, compact = false }: { error: unknown; compact?: boolean }) { return <div className={compact ? 'settings-message settings-message-error' : 'settings-page-state'} role="alert"><strong>Something went wrong.</strong><p>{errorMessage(error)}</p></div> }
function SettingsSkeleton() { return <main className="settings-page" aria-busy="true"><div className="settings-skeleton settings-skeleton-title" /><div className="settings-grid"><div className="settings-skeleton settings-skeleton-card" /><div className="settings-skeleton settings-skeleton-card" /></div></main> }
function errorMessage(error: unknown) { return error instanceof ApiError || error instanceof Error ? error.message : 'Please try again.' }
