import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Save, Tag, Trash2, Workflow } from 'lucide-react'
import { useNavigate, useParams } from 'react-router'
import { getCurrentSession } from '@/api/auth-api'
import { Button } from '@/components/ui/button'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import { queryKeys, resetProjectBoard } from '@/lib/query-keys'
import { projectPath } from '@/routing/route-utils'
import {
  archiveProject,
  createProjectLabel,
  createProjectWorkflowState,
  deleteProject,
  deleteProjectLabel,
  deleteProjectWorkflowState,
  getProject,
  listProjectLabels,
  listProjectWorkflowStates,
  restoreProject,
  updateProject,
  updateProjectLabel,
  updateProjectWorkflowState,
  type Project,
  type ProjectLabel,
  type ProjectWorkflowState,
  type WorkflowStateCategory,
} from '@/api/work-api'
import { useProjectMemberQueries } from '@/features/project-members/useProjectMemberQueries'
import {
  MutationMessage,
  SettingsError,
  SettingsInlineState,
  SettingsSkeleton,
} from '@/pages/SettingsShared'
import { LabelColorPicker } from '@/ui/feature-ui'
import { DEFAULT_LABEL_COLOR } from '@/domain/project-model'

const categories: WorkflowStateCategory[] = ['TODO', 'IN_PROGRESS', 'DONE']

/**
 * Settings that belong to one project, reached from that project rather than
 * from the global settings page. The projectId is a route param so the page can
 * be deep-linked, refreshed, and shared — the previous version kept the choice
 * in component state under /app/settings, which none of those survive.
 */
export function ProjectSettingsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { workspaceId = '', projectId = '' } = useParams()
  const sessionQuery = useQuery({ queryKey: ['current-session'], queryFn: getCurrentSession })
  const projectQuery = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => getProject(projectId),
    enabled: Boolean(projectId),
  })

  if (sessionQuery.isLoading || projectQuery.isLoading) {
    return <SettingsSkeleton />
  }
  if (sessionQuery.error || !sessionQuery.data) {
    return <SettingsError error={sessionQuery.error} />
  }
  if (projectQuery.error || !projectQuery.data) {
    return <SettingsError error={projectQuery.error} />
  }

  const { user, workspace } = sessionQuery.data
  const project = projectQuery.data

  return (
    <main className="settings-page">
      <header className="settings-header">
        <Button
          type="button"
          variant="ghost"
          onClick={() => navigate(projectPath(workspaceId || workspace.id, project.id))}
        >
          <ArrowLeft aria-hidden="true" /> Back to {project.name}
        </Button>
        <div>
          <p className="settings-eyebrow">{workspace.name} · Project</p>
          <h1>{project.name}</h1>
          <p>Rename this project, manage its labels and workflow states, or remove it.</p>
        </div>
      </header>

      <div className="settings-grid">
        <section className="settings-card settings-card-wide" aria-labelledby="project-settings-title">
          <h2 id="project-settings-title" className="settings-visually-hidden">
            Project settings
          </h2>
          <ProjectSettings
            key={project.id}
            project={project}
            workspaceId={workspaceId || workspace.id}
            currentUserId={user.id}
            onProjectChanged={async () => {
              // Both the sidebar list and this page's own header read the
              // project, so a rename has to refresh each of them.
              await Promise.all([
                queryClient.invalidateQueries({ queryKey: ['project', project.id] }),
                queryClient.invalidateQueries({ queryKey: ['projects'] }),
              ])
            }}
            onProjectRemoved={async () => {
              await queryClient.invalidateQueries({ queryKey: ['projects'] })
              navigate('/app', { replace: true })
            }}
          />
        </section>
      </div>
    </main>
  )
}

function ProjectSettings({ project, workspaceId, currentUserId, onProjectChanged, onProjectRemoved }: {
  project: Project
  workspaceId: string
  currentUserId: string
  onProjectChanged: () => Promise<void>
  onProjectRemoved: () => Promise<void>
}) {
  const queryClient = useQueryClient()
  // Mirrors the backend rule: every write below except creating and renaming a
  // label goes through requireOwnedProject*, so a member who is not the project
  // owner would only ever collect a 403 from these controls.
  const { isProjectOwner } = useProjectMemberQueries({
    workspaceId,
    projectId: project.id,
    currentUserId,
    enabled: true,
    loadWorkspaceMembers: false,
  })
  const [name, setName] = useState(project.name)
  const [description, setDescription] = useState(project.description ?? '')
  const labelsQuery = useQuery({
    queryKey: queryKeys.projectLabels(project.id),
    queryFn: () => listProjectLabels(project.id),
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
  })
  const statesQuery = useQuery({
    queryKey: queryKeys.projectWorkflowStates(project.id),
    queryFn: () => listProjectWorkflowStates(project.id),
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
  })
  const updateMutation = useMutation({ mutationFn: () => updateProject(project.id, { name: name.trim(), description: description.trim() }), onSuccess: onProjectChanged })
  const archiveMutation = useMutation({ mutationFn: () => archiveProject(project.id), onSuccess: onProjectChanged })
  const restoreMutation = useMutation({ mutationFn: () => restoreProject(project.id), onSuccess: onProjectChanged })
  const deleteMutation = useMutation({ mutationFn: () => deleteProject(project.id), onSuccess: onProjectRemoved })
  const invalidateLabels = () => queryClient.invalidateQueries({ queryKey: queryKeys.projectLabels(project.id) })
  const invalidateStates = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.projectWorkflowStates(project.id) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.issues(workspaceId, project.id) }),
      resetProjectBoard(queryClient, workspaceId, project.id),
    ])
  }

  return (
    <div className="project-settings-sections">
      {!isProjectOwner ? (
        <SettingsInlineState>You are a member of this project. Only its owner can rename, archive, or delete it, or change its workflow states.</SettingsInlineState>
      ) : null}
      <form className="settings-form" onSubmit={(event) => { event.preventDefault(); updateMutation.mutate() }}>
        <label className="settings-field"><span>Name</span><input required maxLength={160} disabled={!isProjectOwner} value={name} onChange={(event) => setName(event.target.value)} /></label>
        <label className="settings-field"><span>Description</span><textarea maxLength={5000} rows={3} disabled={!isProjectOwner} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
        <MutationMessage mutation={updateMutation} success="Project saved." />
        {isProjectOwner ? <Button type="submit" disabled={updateMutation.isPending || !name.trim()}><Save aria-hidden="true" /> Save project</Button> : null}
      </form>
      <LabelSettings projectId={project.id} labels={labelsQuery.data ?? []} isLoading={labelsQuery.isLoading} canDeleteLabels={isProjectOwner} onChanged={invalidateLabels} />
      <WorkflowSettings projectId={project.id} states={statesQuery.data ?? []} isLoading={statesQuery.isLoading} canManage={isProjectOwner} onChanged={invalidateStates} />
      {isProjectOwner ? (
        <div className="settings-danger-zone">
          <h3>Danger zone</h3>
          <p>Archiving hides this project from the workspace. Deleting permanently removes its issues, comments, labels, and activity.</p>
          <div>{project.archivedAt ? <Button variant="outline" disabled={restoreMutation.isPending} onClick={() => restoreMutation.mutate()}>Restore project</Button> : <Button variant="outline" disabled={archiveMutation.isPending} onClick={() => { if (window.confirm(`Archive ${project.name}?`)) archiveMutation.mutate() }}>Archive project</Button>}<Button variant="destructive" disabled={deleteMutation.isPending} onClick={() => { if (window.confirm(`Permanently delete ${project.name}? This cannot be undone.`)) deleteMutation.mutate() }}><Trash2 aria-hidden="true" /> Delete project</Button></div>
          <MutationMessage mutation={archiveMutation} /><MutationMessage mutation={restoreMutation} /><MutationMessage mutation={deleteMutation} />
        </div>
      ) : null}
    </div>
  )
}

// Creating and renaming a label is open to any active project member; only
// deleting one is owner-gated, so this section stays usable for members.
function LabelSettings({ projectId, labels, isLoading, canDeleteLabels, onChanged }: { projectId: string; labels: ProjectLabel[]; isLoading: boolean; canDeleteLabels: boolean; onChanged: () => Promise<unknown> }) {
  const [newName, setNewName] = useState('')
  const [newColor, setNewColor] = useState<string>(DEFAULT_LABEL_COLOR)
  const createMutation = useMutation({ mutationFn: () => createProjectLabel(projectId, { name: newName.trim(), color: newColor }), onSuccess: async () => { setNewName(''); await onChanged() } })
  const updateMutation = useMutation({ mutationFn: ({ id, name, color }: { id: string; name: string; color: string }) => updateProjectLabel(projectId, id, { name, color }), onSuccess: onChanged })
  const deleteMutation = useMutation({ mutationFn: (id: string) => deleteProjectLabel(projectId, id), onSuccess: onChanged })
  return (
    <section className="settings-subsection"><div className="settings-card-heading"><Tag aria-hidden="true" /><div><h3>Labels</h3><p>Edit or remove project labels.</p></div></div>
      {isLoading ? <SettingsInlineState>Loading labels…</SettingsInlineState> : null}
      <div className="settings-list">{labels.map((label) => <EditableLabel key={label.id} label={label} isPending={updateMutation.isPending || deleteMutation.isPending} canDelete={canDeleteLabels} onSave={(name, color) => updateMutation.mutate({ id: label.id, name, color })} onDelete={() => deleteMutation.mutate(label.id)} />)}</div>
      <form className="settings-inline-form settings-label-form" onSubmit={(event) => { event.preventDefault(); createMutation.mutate() }}>
        <input aria-label="New label name" placeholder="New label" maxLength={60} required value={newName} onChange={(event) => setNewName(event.target.value)} />
        <LabelColorPicker idPrefix="new-label" value={newColor} onChange={setNewColor} />
        <Button size="sm" disabled={createMutation.isPending || !newName.trim()}>Add label</Button>
      </form>
      <MutationMessage mutation={createMutation} /><MutationMessage mutation={updateMutation} /><MutationMessage mutation={deleteMutation} />
    </section>
  )
}

function EditableLabel({ label, isPending, canDelete, onSave, onDelete }: { label: ProjectLabel; isPending: boolean; canDelete: boolean; onSave: (name: string, color: string) => void; onDelete: () => void }) {
  const [name, setName] = useState(label.name); const [color, setColor] = useState(label.color)
  return (
    <div className="settings-list-row settings-edit-row settings-label-row">
      <input aria-label={`Name for ${label.name}`} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} />
      <LabelColorPicker idPrefix={`label-${label.id}`} value={color} onChange={setColor} disabled={isPending} />
      <Button size="icon-xs" variant="ghost" disabled={isPending || !name.trim()} aria-label={`Save ${label.name}`} onClick={() => onSave(name.trim(), color)}><Save aria-hidden="true" /></Button>
      {canDelete ? <Button size="icon-xs" variant="ghost" disabled={isPending} aria-label={`Delete ${label.name}`} onClick={() => { if (window.confirm(`Delete label ${label.name}? It will be removed from every issue.`)) onDelete() }}><Trash2 aria-hidden="true" /></Button> : null}
    </div>
  )
}

// Unlike labels, every workflow-state write is owner-gated, so a member sees
// the states read-only rather than a form that can only fail.
function WorkflowSettings({ projectId, states, isLoading, canManage, onChanged }: { projectId: string; states: ProjectWorkflowState[]; isLoading: boolean; canManage: boolean; onChanged: () => Promise<unknown> }) {
  const [newName, setNewName] = useState(''); const [newCategory, setNewCategory] = useState<WorkflowStateCategory>('TODO')
  const createMutation = useMutation({ mutationFn: () => createProjectWorkflowState(projectId, { name: newName.trim(), category: newCategory }), onSuccess: async () => { setNewName(''); await onChanged() } })
  const updateMutation = useMutation({ mutationFn: ({ id, name, category }: { id: string; name: string; category: WorkflowStateCategory }) => updateProjectWorkflowState(projectId, id, { name, category }), onSuccess: onChanged })
  const deleteMutation = useMutation({ mutationFn: ({ id, replacementId }: { id: string; replacementId: string }) => deleteProjectWorkflowState(projectId, id, replacementId), onSuccess: onChanged })
  return <section className="settings-subsection"><div className="settings-card-heading"><Workflow aria-hidden="true" /><div><h3>Workflow states</h3><p>Deleting a state requires moving its issues to another state.</p></div></div>
    {isLoading ? <SettingsInlineState>Loading workflow states…</SettingsInlineState> : null}
    <div className="settings-list">{states.map((state) => <EditableWorkflowState key={state.id} state={state} states={states} isPending={updateMutation.isPending || deleteMutation.isPending} canManage={canManage} onSave={(name, category) => updateMutation.mutate({ id: state.id, name, category })} onDelete={(replacementId) => deleteMutation.mutate({ id: state.id, replacementId })} />)}</div>
    {canManage ? <form className="settings-inline-form" onSubmit={(event) => { event.preventDefault(); createMutation.mutate() }}><input aria-label="New workflow state name" placeholder="New state" maxLength={60} required value={newName} onChange={(event) => setNewName(event.target.value)} /><select aria-label="New workflow category" value={newCategory} onChange={(event) => setNewCategory(event.target.value)}>{categories.map((category) => <option key={category} value={category}>{category.replace('_', ' ')}</option>)}</select><Button size="sm" disabled={createMutation.isPending || !newName.trim()}>Add state</Button></form> : null}
    <MutationMessage mutation={createMutation} /><MutationMessage mutation={updateMutation} /><MutationMessage mutation={deleteMutation} />
  </section>
}

function EditableWorkflowState({ state, states, isPending, canManage, onSave, onDelete }: { state: ProjectWorkflowState; states: ProjectWorkflowState[]; isPending: boolean; canManage: boolean; onSave: (name: string, category: WorkflowStateCategory) => void; onDelete: (replacementId: string) => void }) {
  const [name, setName] = useState(state.name); const [category, setCategory] = useState(state.category); const replacements = states.filter((item) => item.id !== state.id); const [replacementId, setReplacementId] = useState(replacements[0]?.id ?? '')
  return <div className="settings-workflow-row"><div className="settings-edit-row"><input aria-label={`Name for ${state.name}`} value={name} maxLength={60} disabled={!canManage} onChange={(event) => setName(event.target.value)} /><select aria-label={`Category for ${state.name}`} value={category} disabled={!canManage} onChange={(event) => setCategory(event.target.value)}>{categories.map((item) => <option key={item} value={item}>{item.replace('_', ' ')}</option>)}</select>{canManage ? <Button size="icon-xs" variant="ghost" disabled={isPending || !name.trim()} aria-label={`Save ${state.name}`} onClick={() => onSave(name.trim(), category)}><Save aria-hidden="true" /></Button> : null}</div>{canManage ? <div className="settings-migration-action"><span>Move issues to</span><select aria-label={`Replacement for ${state.name}`} value={replacementId} disabled={replacements.length === 0} onChange={(event) => setReplacementId(event.target.value)}>{replacements.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select><Button size="icon-xs" variant="ghost" disabled={isPending || !replacementId} aria-label={`Delete ${state.name}`} onClick={() => { if (window.confirm(`Delete ${state.name} and move its issues?`)) onDelete(replacementId) }}><Trash2 aria-hidden="true" /></Button></div> : null}</div>
}
