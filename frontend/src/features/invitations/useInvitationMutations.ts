import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWorkspaceInvitation,
  reissueWorkspaceInvitation,
  revokeWorkspaceInvitation,
} from '@/workspace/workspace-api'
import type { CreateWorkspaceInvitationFormValues } from '@/features/project-shell/project-model'
import { invitationUrl } from '@/features/project-shell/display-utils'

export function useInvitationMutations({
  workspaceId,
  onInvitationLink,
}: {
  workspaceId: string | null
  onInvitationLink: (link: string | null) => void
}) {
  const queryClient = useQueryClient()
  const invalidateInvitations = () =>
    queryClient.invalidateQueries({ queryKey: ['workspace-invitations', workspaceId] })

  const createInvitationMutation = useMutation({
    mutationFn: (values: CreateWorkspaceInvitationFormValues) =>
      createWorkspaceInvitation({ email: values.email.trim(), role: values.role }),
    onSuccess: async (invitation) => {
      onInvitationLink(invitationUrl(invitation))
      await invalidateInvitations()
    },
  })

  const reissueInvitationMutation = useMutation({
    mutationFn: reissueWorkspaceInvitation,
    onSuccess: async (invitation) => {
      onInvitationLink(invitationUrl(invitation))
      await invalidateInvitations()
    },
  })

  const revokeInvitationMutation = useMutation({
    mutationFn: revokeWorkspaceInvitation,
    onSuccess: async () => {
      onInvitationLink(null)
      await invalidateInvitations()
    },
  })

  return {
    createInvitationMutation,
    reissueInvitationMutation,
    revokeInvitationMutation,
  }
}
