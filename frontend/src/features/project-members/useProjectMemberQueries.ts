import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { PROJECT_METADATA_STALE_TIME_MS } from '@/lib/query-config'
import {
  listProjectMembers,
  listWorkspaceMembers,
  type ProjectMember,
  type WorkspaceMember,
} from '@/work/work-api'

const EMPTY_PROJECT_MEMBERS: ProjectMember[] = []
const EMPTY_WORKSPACE_MEMBERS: WorkspaceMember[] = []

export function useProjectMemberQueries({
  workspaceId,
  projectId,
  currentUserId,
  enabled,
  loadWorkspaceMembers,
}: {
  workspaceId: string | null
  projectId: string | null
  currentUserId: string | null
  enabled: boolean
  loadWorkspaceMembers: boolean
}) {
  const projectMembersQuery = useQuery({
    queryKey: ['project-members', projectId],
    queryFn: () => listProjectMembers(projectId ?? ''),
    enabled,
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
    retry: false,
  })
  const workspaceMembersQuery = useQuery({
    queryKey: ['workspace-members', workspaceId],
    queryFn: listWorkspaceMembers,
    enabled: enabled && loadWorkspaceMembers,
    staleTime: PROJECT_METADATA_STALE_TIME_MS,
    retry: false,
  })
  const projectMembers = projectMembersQuery.data ?? EMPTY_PROJECT_MEMBERS
  const workspaceMembers = workspaceMembersQuery.data ?? EMPTY_WORKSPACE_MEMBERS
  const activeProjectMembers = useMemo(
    () => projectMembers.filter((member) => member.status === 'ACTIVE'),
    [projectMembers],
  )
  const currentProjectMember = projectMembers.find(
    (member) => member.userId === currentUserId && member.status === 'ACTIVE',
  )
  const activeProjectMemberUserIds = useMemo(
    () => new Set(activeProjectMembers.map((member) => member.userId)),
    [activeProjectMembers],
  )
  const addableWorkspaceMembers = useMemo(
    () => workspaceMembers.filter(
      (member) => member.status === 'ACTIVE' && !activeProjectMemberUserIds.has(member.userId),
    ),
    [activeProjectMemberUserIds, workspaceMembers],
  )
  return {
    projectMembersQuery,
    workspaceMembersQuery,
    projectMembers,
    workspaceMembers,
    activeProjectMembers,
    canManageProjectMembers: currentProjectMember?.role === 'OWNER',
    addableWorkspaceMembers,
  }
}
