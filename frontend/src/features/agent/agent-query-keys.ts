type Id = string | null | undefined

export const agentQueryKeys = {
  runs: (workspaceId: Id, projectId: Id) => ['agent-runs', workspaceId, projectId] as const,
  run: (workspaceId: Id, runId: Id) => ['agent-run', workspaceId, runId] as const,
  /**
   * How hard the agent worked on one version. Only the response that produced the
   * version says so, and the run's detail does not, so it is kept here when it
   * arrives and is gone after a reload.
   */
  stats: (workspaceId: Id, runId: Id, version: number) =>
    ['agent-run-stats', workspaceId, runId, version] as const,
}
