import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from '@playwright/test'

const PASSWORD = 'password123'

type Workspace = {
  id: string
  name: string
  slug: string
  role: string
}

type User = {
  id: string
  email: string
  displayName: string
}

type AuthSession = {
  accessToken: string
  refreshToken: string
  user: User
  workspace: Workspace
}

type Project = {
  id: string
  name: string
}

type WorkflowState = {
  id: string
  name: string
  category: 'TODO' | 'IN_PROGRESS' | 'DONE'
}

type Board = {
  columns: Array<{
    workflowState: WorkflowState
    issues: Array<{ id: string; title: string }>
  }>
}

function uniqueValue(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` }
}

async function requireJson<T>(response: Awaited<ReturnType<APIRequestContext['post']>>) {
  const body = await response.text()
  expect(response.ok(), body).toBeTruthy()
  return JSON.parse(body) as T
}

async function registerUser(
  request: APIRequestContext,
  workspaceName: string,
) {
  const email = `${uniqueValue('e2e')}@example.com`
  const response = await request.post('/api/auth/register', {
    data: {
      email,
      password: PASSWORD,
      displayName: 'E2E User',
      workspaceName,
    },
  })
  return requireJson<AuthSession>(response)
}

async function createWorkspace(
  request: APIRequestContext,
  accessToken: string,
  name: string,
) {
  const response = await request.post('/api/workspaces', {
    data: { name },
    headers: authorization(accessToken),
  })
  return requireJson<Workspace>(response)
}

async function switchWorkspace(
  request: APIRequestContext,
  session: AuthSession,
  workspaceId: string,
) {
  const response = await request.post(`/api/workspaces/${workspaceId}/switch`, {
    data: { refreshToken: session.refreshToken },
    headers: authorization(session.accessToken),
  })
  return requireJson<AuthSession>(response)
}

async function createProject(
  request: APIRequestContext,
  accessToken: string,
  name: string,
) {
  const response = await request.post('/api/projects', {
    data: { name, description: 'Created by Playwright' },
    headers: authorization(accessToken),
  })
  return requireJson<Project>(response)
}

async function listWorkflowStates(
  request: APIRequestContext,
  accessToken: string,
  projectId: string,
) {
  const response = await request.get(`/api/projects/${projectId}/workflow-states`, {
    headers: authorization(accessToken),
  })
  const body = await response.text()
  expect(response.ok(), body).toBeTruthy()
  return JSON.parse(body) as WorkflowState[]
}

async function createIssue(
  request: APIRequestContext,
  accessToken: string,
  projectId: string,
  workflowStateId: string,
  title: string,
) {
  const response = await request.post('/api/issues', {
    data: { projectId, workflowStateId, title },
    headers: authorization(accessToken),
  })
  return requireJson<{ id: string }>(response)
}

async function login(page: Page, email: string) {
  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/app(?:\/|$)/)
}

test('logs in and switches between workspaces', async ({ page, request }) => {
  const homeName = uniqueValue('Home workspace')
  const targetName = uniqueValue('Target workspace')
  const registered = await registerUser(request, homeName)
  const homeProject = await createProject(request, registered.accessToken, 'Home project')
  const targetWorkspace = await createWorkspace(request, registered.accessToken, targetName)
  const targetSession = await switchWorkspace(request, registered, targetWorkspace.id)
  const targetProject = await createProject(request, targetSession.accessToken, 'Target project')
  await switchWorkspace(request, targetSession, registered.workspace.id)

  await login(page, registered.user.email)
  await expect(page.locator('.workspace-select-label strong')).toHaveText(homeName)
  await expect(page.getByRole('heading', { name: homeProject.name })).toBeVisible()

  await page.locator('.workspace-switcher').click()
  await page.getByRole('menuitem', { name: new RegExp(targetName) }).click()

  await expect(page.locator('.workspace-select-label strong')).toHaveText(targetName)
  await expect(page).toHaveURL(new RegExp(
    `/app/workspaces/${targetWorkspace.id}/projects/${targetProject.id}`,
  ))
  await expect(page.getByRole('heading', { name: targetProject.name })).toBeVisible()
})

test('drags an issue between workflow columns and persists the move', async ({
  page,
  request,
}) => {
  const registered = await registerUser(request, uniqueValue('Kanban workspace'))
  const project = await createProject(request, registered.accessToken, 'Kanban project')
  const workflowStates = await listWorkflowStates(
    request,
    registered.accessToken,
    project.id,
  )
  const todo = workflowStates.find((state) => state.category === 'TODO')
  const inProgress = workflowStates.find((state) => state.category === 'IN_PROGRESS')
  expect(todo).toBeTruthy()
  expect(inProgress).toBeTruthy()
  const issueTitle = uniqueValue('Move this issue')
  await createIssue(request, registered.accessToken, project.id, todo!.id, issueTitle)

  await login(page, registered.user.email)
  const targetColumn = page.getByRole('region', { name: `${inProgress!.name} issues` })
  await page.getByRole('button', { name: `Move ${issueTitle}` })
    .dragTo(targetColumn.locator('.kanban-column-body'))

  await expect(targetColumn.getByText(issueTitle)).toBeVisible()
  await expect.poll(async () => {
    const response = await request.get(`/api/issues/board?projectId=${project.id}`, {
      headers: authorization(registered.accessToken),
    })
    const board = await response.json() as Board
    return board.columns.find((column) => column.workflowState.id === inProgress!.id)
      ?.issues.some((issue) => issue.title === issueTitle)
  }).toBe(true)
})

test('opens project analytics and persists the selected range in the URL', async ({
  page,
  request,
}) => {
  const registered = await registerUser(request, uniqueValue('Analytics workspace'))
  const project = await createProject(request, registered.accessToken, 'Analytics project')
  const workflowStates = await listWorkflowStates(
    request,
    registered.accessToken,
    project.id,
  )
  const todo = workflowStates.find((state) => state.category === 'TODO')
  const done = workflowStates.find((state) => state.category === 'DONE')
  expect(todo).toBeTruthy()
  expect(done).toBeTruthy()
  await createIssue(request, registered.accessToken, project.id, todo!.id, 'Open work')
  await createIssue(request, registered.accessToken, project.id, done!.id, 'Completed work')

  await login(page, registered.user.email)
  await page.getByRole('button', { name: 'Analytics' }).click()

  await expect(page).toHaveURL(new RegExp(`/projects/${project.id}/analytics$`))
  await expect(page.getByRole('heading', { name: 'Analytics' })).toBeVisible()
  await expect(page.locator('.analytics-metric').filter({ hasText: 'Active issues' })
    .locator('strong')).toHaveText('2')
  await expect(page.locator('.analytics-metric').filter({ hasText: 'Completed' })
    .locator('strong')).toHaveText('1')

  await page.getByRole('button', { name: '7d' }).click()
  await expect(page).toHaveURL(new RegExp(`/projects/${project.id}/analytics[?]range=7$`))
  await expect(page.getByRole('button', { name: '7d' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})
