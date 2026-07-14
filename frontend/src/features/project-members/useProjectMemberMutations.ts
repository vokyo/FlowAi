import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  addProjectMember,
  removeProjectMember,
  updateProjectMember,
} from '@/work/work-api'

export function useProjectMemberMutations({
  workspaceId,
  currentUserId,
  onCurrentUserRemoved,
}: {
  workspaceId: string | null
  currentUserId: string | null
  onCurrentUserRemoved: () => void
}) {
  const queryClient = useQueryClient()
  const invalidateProjectMemberQueries = (projectId: string) =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['project-members', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['projects', workspaceId] }),
      queryClient.invalidateQueries({ queryKey: ['issues', workspaceId, projectId] }),
      queryClient.invalidateQueries({ queryKey: ['issue'] }),
    ])

  const addMemberMutation = useMutation({
    mutationFn: ({ projectId, userId }: { projectId: string; userId: string }) =>
      addProjectMember(projectId, { userId, role: 'MEMBER' }),
    onSuccess: async (_, variables) => invalidateProjectMemberQueries(variables.projectId),
  })

  const updateMemberMutation = useMutation({
    mutationFn: ({
      projectId,
      memberId,
      role,
    }: {
      projectId: string
      memberId: string
      role: 'OWNER' | 'MEMBER'
    }) => updateProjectMember(projectId, memberId, { role }),
    onSuccess: async (_, variables) => invalidateProjectMemberQueries(variables.projectId),
  })

  const removeMemberMutation = useMutation({
    mutationFn: ({ projectId, memberId }: { projectId: string; memberId: string; userId: string }) =>
      removeProjectMember(projectId, memberId),
    onSuccess: async (_, variables) => {
      if (variables.userId === currentUserId) onCurrentUserRemoved()
      await invalidateProjectMemberQueries(variables.projectId)
    },
  })

  const resetMemberMutations = () => {
    addMemberMutation.reset()
    updateMemberMutation.reset()
    removeMemberMutation.reset()
  }

  return { addMemberMutation, updateMemberMutation, removeMemberMutation, resetMemberMutations }
}
