import { useQuery } from '@tanstack/react-query'
import { getCurrentSession, type AuthWorkspace } from '@/auth/auth-api'
import { getProject, listProjects, type Project } from '@/work/work-api'
import { listWorkspaces } from '@/workspace/workspace-api'

const EMPTY_PROJECTS: Project[] = []
const EMPTY_WORKSPACES: AuthWorkspace[] = []

export function useProjectShellQueries({
  routeWorkspaceId,
  routeProjectId,
}: {
  routeWorkspaceId: string | undefined
  routeProjectId: string | undefined
}) {
  const currentSessionQuery = useQuery({
    queryKey: ['current-session'],
    queryFn: getCurrentSession,
    retry: false,
  })
  const currentWorkspace = currentSessionQuery.data?.workspace ?? null
  const currentUser = currentSessionQuery.data?.user ?? null
  const currentWorkspaceId = currentWorkspace?.id ?? null
  const routeMatchesCurrentWorkspace =
    !routeWorkspaceId || !currentWorkspaceId || routeWorkspaceId === currentWorkspaceId
  const canLoadCurrentWorkspace = Boolean(currentWorkspaceId && routeMatchesCurrentWorkspace)

  const workspacesQuery = useQuery({
    queryKey: ['workspaces'],
    queryFn: listWorkspaces,
    enabled: currentSessionQuery.isSuccess,
    retry: false,
  })
  const projectsQuery = useQuery({
    queryKey: ['projects', currentWorkspaceId],
    queryFn: () => listProjects(),
    enabled: canLoadCurrentWorkspace,
    retry: false,
  })
  const workspaces = workspacesQuery.data ?? EMPTY_WORKSPACES
  const projects = projectsQuery.data ?? EMPTY_PROJECTS
  const selectedProjectFromList = routeProjectId
    ? projects.find((project) => project.id === routeProjectId) ?? null
    : projects[0] ?? null
  const selectedProjectId = selectedProjectFromList?.id ?? null
  const selectedProjectQuery = useQuery({
    queryKey: ['project', selectedProjectId],
    queryFn: () => getProject(selectedProjectId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    retry: false,
  })

  return {
    currentSessionQuery,
    currentWorkspace,
    currentWorkspaceId,
    currentUser,
    routeMatchesCurrentWorkspace,
    canLoadCurrentWorkspace,
    workspacesQuery,
    workspaces,
    projectsQuery,
    projects,
    selectedProjectQuery,
    selectedProject: selectedProjectQuery.data ?? selectedProjectFromList,
    selectedProjectId,
  }
}
