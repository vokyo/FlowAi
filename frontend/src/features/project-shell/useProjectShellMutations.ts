import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { setAccessToken } from '@/auth/access-token'
import { createProject } from '@/api/work-api'
import { createWorkspace, switchWorkspace } from '@/api/workspace-api'
import type { CreateProjectFormValues, CreateWorkspaceFormValues } from '@/domain/project-model'
import { pathWithSearchParams, projectPath } from '@/routing/route-utils'

export function useProjectShellMutations({
  workspaceId,
  workViewSearchParams,
  onSessionChanged,
  onWorkspaceCreated,
  onProjectCreated,
}: {
  workspaceId: string | null
  workViewSearchParams: URLSearchParams
  onSessionChanged: () => void
  onWorkspaceCreated: () => void
  onProjectCreated: () => void
}) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const applyWorkspaceSession = async (response: { accessToken: string }) => {
    setAccessToken(response.accessToken)
    await queryClient.cancelQueries()
    queryClient.clear()
    onSessionChanged()
    navigate(pathWithSearchParams('/app', workViewSearchParams), { replace: true })
  }

  const switchWorkspaceMutation = useMutation({
    mutationFn: switchWorkspace,
    onSuccess: applyWorkspaceSession,
  })

  const createWorkspaceMutation = useMutation({
    mutationFn: async (values: CreateWorkspaceFormValues) => {
      const workspace = await createWorkspace({ name: values.name.trim() })
      return switchWorkspace(workspace.id)
    },
    onSuccess: async (response) => {
      onWorkspaceCreated()
      await applyWorkspaceSession(response)
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: ['workspaces'] })
    },
  })

  const createProjectMutation = useMutation({
    mutationFn: (values: CreateProjectFormValues) =>
      createProject({ name: values.name.trim(), description: values.description.trim() || undefined }),
    onSuccess: async (project) => {
      onProjectCreated()
      await queryClient.invalidateQueries({ queryKey: ['projects', workspaceId] })
      if (workspaceId) {
        navigate(pathWithSearchParams(projectPath(workspaceId, project.id), workViewSearchParams))
      }
    },
  })

  return { switchWorkspaceMutation, createWorkspaceMutation, createProjectMutation }
}
