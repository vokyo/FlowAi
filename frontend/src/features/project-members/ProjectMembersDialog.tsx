import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import { Loader2, UserMinus, UserPlus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { Project, ProjectMember, WorkspaceMember } from '@/work/work-api'
import {
  addProjectMemberFormSchema,
  type AddProjectMemberFormValues,
} from '@/features/project-shell/project-model'
import {
  formatDate,
  formatProjectRole,
  formatStatus,
  getInitials,
} from '@/features/project-shell/display-utils'
import {
  ErrorState,
  InlineNotice,
  InlineState,
  ModalShell,
  ProjectMemberMutationErrorState,
} from '@/features/project-shell/feature-ui'

export function ProjectMembersDialog({
  isOpen,
  selectedProject,
  projectMembers,
  workspaceMembers,
  addableWorkspaceMembers,
  onSubmit,
  onUpdateRole,
  onRemove,
  onClose,
  canManageMembers,
  isLoadingProjectMembers,
  isLoadingWorkspaceMembers,
  projectMembersError,
  workspaceMembersError,
  isSubmitting,
  isMutating,
  updatingMemberId,
  removingMemberId,
  addError,
  updateError,
  removeError,
}: {
  isOpen: boolean
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  workspaceMembers: WorkspaceMember[]
  addableWorkspaceMembers: WorkspaceMember[]
  onSubmit: (values: AddProjectMemberFormValues) => Promise<void>
  onUpdateRole: (memberId: string, role: 'OWNER' | 'MEMBER') => Promise<void>
  onRemove: (member: ProjectMember) => Promise<void>
  onClose: () => void
  canManageMembers: boolean
  isLoadingProjectMembers: boolean
  isLoadingWorkspaceMembers: boolean
  projectMembersError: Error | null
  workspaceMembersError: Error | null
  isSubmitting: boolean
  isMutating: boolean
  updatingMemberId: string | null
  removingMemberId: string | null
  addError: Error | null
  updateError: Error | null
  removeError: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<AddProjectMemberFormValues>({
    resolver: zodResolver(addProjectMemberFormSchema),
    defaultValues: {
      userId: '',
    },
  })
  const selectedUserId = useWatch({ control, name: 'userId' }) ?? ''

  useEffect(() => {
    if (isOpen) {
      reset({ userId: '' })
    }
  }, [isOpen, reset])

  async function submitProjectMember(values: AddProjectMemberFormValues) {
    try {
      await onSubmit(values)
      reset({ userId: '' })
    } catch {
      // The mutation error is rendered below so the selected member remains visible.
    }
  }

  if (!isOpen) {
    return null
  }

  const activeWorkspaceMemberCount = workspaceMembers.filter(
    (member) => member.status === 'ACTIVE',
  ).length
  const activeOwnerCount = projectMembers.filter(
    (member) => member.status === 'ACTIVE' && member.role === 'OWNER',
  ).length
  const canSubmit = canManageMembers && Boolean(selectedUserId) && !isMutating
  const workspaceMemberCountLabel = isLoadingWorkspaceMembers
    ? 'Loading workspace members'
    : `${activeWorkspaceMemberCount} active workspace members`

  return (
    <ModalShell
      title="Project members"
      eyebrow={selectedProject?.name ?? 'Project'}
      onClose={onClose}
      variant="members"
      isCloseDisabled={isMutating}
    >
      <div className="project-members-dialog">
        {isLoadingProjectMembers ? <InlineState>Loading project members.</InlineState> : null}
        {projectMembersError ? <ErrorState error={projectMembersError} /> : null}
        {!isLoadingProjectMembers && !projectMembersError ? (
          <div className="project-member-list" aria-label="Project members">
            {projectMembers.map((member) => {
              const isActive = member.status === 'ACTIVE'
              const isLastActiveOwner =
                isActive && member.role === 'OWNER' && activeOwnerCount === 1
              const isUpdating = updatingMemberId === member.id
              const isRemoving = removingMemberId === member.id

              return (
                <div className="project-member-row" key={member.id}>
                  <span className="workspace-avatar project-member-avatar" aria-hidden="true">
                    {getInitials(member.displayName || member.email)}
                  </span>
                  <span className="project-member-person">
                    <strong>{member.displayName || member.email}</strong>
                    <small>
                      {member.email} - Joined {formatDate(member.joinedAt)}
                    </small>
                  </span>
                  <span className="project-member-meta">
                    {canManageMembers && isActive ? (
                      <select
                        className="project-member-role-select"
                        value={member.role}
                        disabled={isMutating || isLastActiveOwner}
                        onChange={(event) => {
                          const role = event.target.value as 'OWNER' | 'MEMBER'
                          if (role === member.role) {
                            return
                          }

                          void onUpdateRole(member.id, role).catch(() => {
                            // The mutation error is rendered below the member list.
                          })
                        }}
                        aria-label={`Role for ${member.displayName || member.email}`}
                        title={
                          isLastActiveOwner
                            ? 'A project must have at least one active owner'
                            : 'Project role'
                        }
                      >
                        <option value="OWNER">Owner</option>
                        <option value="MEMBER">Member</option>
                      </select>
                    ) : (
                      <span className="project-role-badge" data-role={member.role}>
                        {formatProjectRole(member.role)}
                      </span>
                    )}
                    <span className="project-member-status">{formatStatus(member.status)}</span>
                    {canManageMembers && isActive ? (
                      <Button
                        type="button"
                        variant="destructive"
                        size="icon-sm"
                        disabled={isMutating || isLastActiveOwner}
                        onClick={() => {
                          void onRemove(member).catch(() => {
                            // The mutation error is rendered below the member list.
                          })
                        }}
                        aria-label={`Remove ${member.displayName || member.email}`}
                        title={
                          isLastActiveOwner
                            ? 'A project must have at least one active owner'
                            : 'Remove member'
                        }
                      >
                        {isRemoving ? (
                          <Loader2 aria-hidden="true" className="auth-spin" />
                        ) : (
                          <UserMinus aria-hidden="true" />
                        )}
                      </Button>
                    ) : null}
                    {isUpdating ? <Loader2 aria-hidden="true" className="auth-spin" /> : null}
                  </span>
                </div>
              )
            })}
          </div>
        ) : null}

        {updateError ? (
          <ProjectMemberMutationErrorState error={updateError} action="update" />
        ) : null}
        {removeError ? (
          <ProjectMemberMutationErrorState error={removeError} action="remove" />
        ) : null}

        <section className="project-member-add-section">
          <div className="project-member-add-heading">
            <h3>Add member</h3>
            <span className="app-state">{workspaceMemberCountLabel}</span>
          </div>
          {isLoadingProjectMembers ? (
            <InlineNotice>Checking project role.</InlineNotice>
          ) : !canManageMembers ? (
            <InlineNotice>Only project owners can add members.</InlineNotice>
          ) : (
            <form
              className="project-member-add-form"
              onSubmit={handleSubmit(submitProjectMember)}
              noValidate
            >
              <label className="app-field">
                Workspace member
                <select
                  disabled={
                    isMutating ||
                    isLoadingWorkspaceMembers ||
                    Boolean(workspaceMembersError) ||
                    addableWorkspaceMembers.length === 0
                  }
                  {...register('userId')}
                >
                  <option value="">
                    {isLoadingWorkspaceMembers ? 'Loading members' : 'Select member'}
                  </option>
                  {addableWorkspaceMembers.map((member) => (
                    <option key={member.id} value={member.userId}>
                      {member.displayName || member.email} - {member.email}
                    </option>
                  ))}
                </select>
              </label>
              {errors.userId?.message ? (
                <InlineNotice tone="warning">{errors.userId.message}</InlineNotice>
              ) : null}
              <Button type="submit" disabled={!canSubmit}>
                {isSubmitting ? (
                  <Loader2 aria-hidden="true" className="auth-spin" />
                ) : (
                  <UserPlus aria-hidden="true" />
                )}
                Add member
              </Button>
            </form>
          )}
          {workspaceMembersError ? <ErrorState error={workspaceMembersError} /> : null}
          {canManageMembers &&
          !isLoadingWorkspaceMembers &&
          !workspaceMembersError &&
          addableWorkspaceMembers.length === 0 ? (
            <InlineNotice>All active workspace members are active in this project.</InlineNotice>
          ) : null}
          {addError ? <ProjectMemberMutationErrorState error={addError} action="add" /> : null}
        </section>
      </div>
    </ModalShell>
  )
}
import { useEffect } from 'react'
