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

type WorkspaceInvitation = {
  token: string
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

async function createWorkspaceInvitation(
  request: APIRequestContext,
  accessToken: string,
  email: string,
) {
  const response = await request.post('/api/workspaces/current/invitations', {
    data: { email, role: 'MEMBER' },
    headers: authorization(accessToken),
  })
  return requireJson<WorkspaceInvitation>(response)
}

async function switchWorkspace(
  request: APIRequestContext,
  session: AuthSession,
  workspaceId: string,
) {
  const response = await request.post(`/api/workspaces/${workspaceId}/switch`, {
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
  assigneeUserId?: string,
) {
  const response = await request.post('/api/issues', {
    data: { projectId, workflowStateId, title, assigneeUserId },
    headers: authorization(accessToken),
  })
  return requireJson<{ id: string }>(response)
}

async function dragIssueToColumn(page: Page, issueTitle: string, columnName: string) {
  const escapedColumnName = columnName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const targetColumn = page.getByRole('region', {
    name: new RegExp(`^${escapedColumnName} issues$`, 'i'),
  })
  const dragHandle = page.getByRole('button', { name: `Move ${issueTitle}` })
  const targetColumnBody = targetColumn.locator('.kanban-column-body')
  await expect(dragHandle).toBeVisible()
  await expect(dragHandle).toBeEnabled()
  await expect(targetColumnBody).toBeVisible()
  let sourceBox = await dragHandle.boundingBox()
  let targetBox = await targetColumnBody.boundingBox()
  await expect.poll(async () => {
    sourceBox = await dragHandle.boundingBox()
    targetBox = await targetColumnBody.boundingBox()
    return Boolean(sourceBox && targetBox)
  }).toBe(true)
  if (!sourceBox || !targetBox) {
    throw new Error('Could not resolve stable drag coordinates')
  }

  await page.mouse.move(
    sourceBox.x + sourceBox.width / 2,
    sourceBox.y + sourceBox.height / 2,
  )
  await page.mouse.down()
  try {
    await page.mouse.move(
      sourceBox.x + sourceBox.width / 2 + 15,
      sourceBox.y + sourceBox.height / 2,
      { steps: 5 },
    )
    await page.mouse.move(
      targetBox.x + targetBox.width / 2,
      targetBox.y + Math.min(100, targetBox.height / 2),
      { steps: 20 },
    )
    await expect(targetColumnBody).toHaveAttribute('data-over', 'true')
  } finally {
    await page.mouse.up()
  }

  await expect(targetColumn.getByText(issueTitle)).toBeVisible()
  await expect(page.getByRole('region', { name: 'Project board' }))
    .toHaveAttribute('aria-busy', 'false')
}

async function login(page: Page, email: string) {
  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/app(?:\/|$)/)
}

test('registers and completes the core project and issue workflow', async ({ page }) => {
  const workspaceName = uniqueValue('Product workspace')
  const projectName = uniqueValue('Web launch')
  const issueTitle = uniqueValue('Build onboarding')
  const updatedTitle = `${issueTitle} updated`
  const comment = uniqueValue('Ready for review')
  const email = `${uniqueValue('journey')}@example.com`
  let issueListRequestCount = 0
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'GET' && url.pathname === '/api/issues') {
      issueListRequestCount += 1
    }
  })

  await page.goto('/register')
  await page.getByLabel('Name').fill('Journey User')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByLabel('Workspace').fill(workspaceName)
  await page.getByRole('button', { name: 'Create account' }).click()

  await expect(page).toHaveURL(/\/app(?:\/|$)/)
  await expect(page.locator('.workspace-select-label strong')).toHaveText(workspaceName)

  await page.getByRole('button', { name: 'Create project' }).click()
  const projectDialog = page.getByRole('dialog', { name: 'Create project' })
  await projectDialog.getByLabel('Name').fill(projectName)
  await projectDialog.getByLabel('Description').fill('A project created through the UI')
  await projectDialog.getByRole('button', { name: 'Create project' }).click()

  await expect(page.getByRole('heading', { name: projectName })).toBeVisible()
  expect(issueListRequestCount).toBe(0)
  await page.getByRole('button', { name: 'Create issue', exact: true }).click()
  const issueDialog = page.getByRole('dialog', { name: 'New issue' })
  await issueDialog.getByPlaceholder('Issue title').fill(issueTitle)
  await issueDialog.getByPlaceholder('Add description...').fill('Initial description')
  await issueDialog.getByRole('button', { name: 'Create issue' }).click()

  await page.getByText(issueTitle, { exact: true }).click()
  await expect(page.getByRole('heading', { name: issueTitle })).toBeVisible()
  expect(issueListRequestCount).toBe(0)
  await page.getByRole('button', { name: 'Edit' }).click()
  await page.getByLabel('Issue title').fill(updatedTitle)
  await page.getByLabel('Issue description').fill('Updated through the issue detail view')
  await page.getByRole('button', { name: 'Save' }).click()

  await expect(page.getByRole('heading', { name: updatedTitle })).toBeVisible()
  await expect(page.getByText('Updated through the issue detail view')).toBeVisible()
  await page.getByLabel('Add comment').fill(comment)
  await page.getByRole('button', { name: 'Comment' }).click()
  await expect(page.getByText(comment, { exact: true })).toBeVisible()

  await page.reload()
  await expect(page.getByRole('heading', { name: updatedTitle })).toBeVisible()
  await expect(page.getByText(comment, { exact: true })).toBeVisible()
})

test('creates an account from an invitation and joins the invited workspace', async ({
  page,
  request,
}) => {
  const workspaceName = uniqueValue('Inviting workspace')
  const owner = await registerUser(request, workspaceName)
  const invitedEmail = `${uniqueValue('invited')}@example.com`
  const invitation = await createWorkspaceInvitation(
    request,
    owner.accessToken,
    invitedEmail,
  )

  await page.goto(`/invite/${invitation.token}`)
  await expect(page.getByRole('heading', { name: `Join ${workspaceName}` })).toBeVisible()
  await page.getByRole('link', { name: 'Create account' }).click()
  await page.getByLabel('Name').fill('Invited User')
  await expect(page.getByLabel('Email')).toHaveValue(invitedEmail)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create account and join' }).click()

  await expect(page).toHaveURL(new RegExp(`/invite/${invitation.token}$`))
  await expect(page.getByText('Invitation already accepted')).toBeVisible()
  await page.goto('/app')
  await expect(page.locator('.workspace-select-label strong')).toHaveText(workspaceName)
})

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
  await expect(page.getByText(homeProject.name, { exact: true })).toHaveCount(0)
})

test('manages profile and project configuration from settings', async ({ page, request }) => {
  const registered = await registerUser(request, uniqueValue('Settings workspace'))
  const project = await createProject(request, registered.accessToken, 'Settings project')

  await login(page, registered.user.email)
  await expect(page).toHaveURL(/\/app\/workspaces\/[^/]+\/projects\/[^/?]+(?:[?]|$)/)
  await page.getByRole('button', { name: 'Open user menu' }).click()
  await page.getByRole('menu', { name: 'User menu' })
    .getByRole('menuitem', { name: 'Settings' }).click()
  await expect(page).toHaveURL(/\/app\/settings$/)
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible()

  await page.getByLabel('Display name').fill('Updated E2E User')
  await page.getByRole('button', { name: 'Save profile' }).click()
  await expect(page.getByText('Profile saved.')).toBeVisible()

  await expect(page.locator('.settings-card-wide > label.settings-field select')).toHaveValue(project.id)
  await page.getByLabel('Name', { exact: true }).fill('Renamed settings project')
  await page.getByLabel('Description').fill('Managed from the settings route')
  await page.getByRole('button', { name: 'Save project' }).click()
  await expect(page.getByText('Project saved.')).toBeVisible()

  await page.getByLabel('New label name').fill('E2E label')
  await page.getByRole('button', { name: 'Add label' }).click()
  await expect(page.getByLabel('Name for E2E label')).toHaveValue('E2E label')

  await page.getByLabel('New workflow state name').fill('Ready to ship')
  await page.getByRole('button', { name: 'Add state' }).click()
  await expect(page.getByLabel('Name for Ready to ship')).toHaveValue('Ready to ship')

  await page.getByRole('button', { name: 'Back to workspace' }).click()
  await expect(page.getByRole('heading', { name: 'Renamed settings project' })).toBeVisible()
})

test('drags issues in My issues and Unassigned boards and persists the moves', async ({
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
  const myIssueTitle = uniqueValue('Move my issue')
  const unassignedIssueTitle = uniqueValue('Move unassigned issue')
  await createIssue(
    request,
    registered.accessToken,
    project.id,
    todo!.id,
    myIssueTitle,
    registered.user.id,
  )
  await createIssue(
    request,
    registered.accessToken,
    project.id,
    todo!.id,
    unassignedIssueTitle,
  )

  await login(page, registered.user.email)
  await expect(page.getByRole('button', { name: `Move ${myIssueTitle}` })).toBeVisible()
  const views = page.getByRole('navigation', { name: 'Views' })
  await views.getByRole('button', { name: 'My issues', exact: true }).click()
  await dragIssueToColumn(page, myIssueTitle, inProgress!.name)

  await views.getByRole('button', { name: 'Unassigned', exact: true }).click()
  await dragIssueToColumn(page, unassignedIssueTitle, inProgress!.name)

  await expect.poll(async () => {
    const response = await request.get(`/api/issues/board?projectId=${project.id}`, {
      headers: authorization(registered.accessToken),
    })
    const board = await response.json() as Board
    const targetIssues = board.columns.find(
      (column) => column.workflowState.id === inProgress!.id,
    )?.issues ?? []
    return [myIssueTitle, unassignedIssueTitle].every((title) =>
      targetIssues.some((issue) => issue.title === title),
    )
  }).toBe(true)
})

test('opens the mobile navigation drawer and closes it after navigation', async ({
  page,
  request,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const registered = await registerUser(request, uniqueValue('Mobile workspace'))
  await createProject(request, registered.accessToken, 'Mobile project')

  await login(page, registered.user.email)
  const navigationTrigger = page.getByRole('button', { name: 'Open navigation' })
  await expect(navigationTrigger).toBeVisible()
  await navigationTrigger.click()
  await expect(navigationTrigger).toHaveAttribute('aria-expanded', 'true')

  await page.getByRole('navigation', { name: 'Views' })
    .getByRole('button', { name: 'My issues', exact: true }).click()

  await expect(page).toHaveURL(/[?&]view=mine(?:&|$)/)
  await expect(navigationTrigger).toHaveAttribute('aria-expanded', 'false')
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
  await page.getByRole('button', { name: 'Analytics', exact: true }).click()

  await expect(page).toHaveURL(new RegExp(`/projects/${project.id}/analytics$`))
  await expect(page.getByRole('heading', { name: 'Analytics' })).toBeVisible()
  await expect(page.locator('.analytics-metric').filter({ hasText: 'Active issues' })
    .locator('strong')).toHaveText('2')
  await expect(page.locator('.analytics-metric').filter({ hasText: 'Completed' })
    .locator('strong')).toHaveText('1')

  await page.getByRole('button', { name: '7d', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/projects/${project.id}/analytics[?]range=7$`))
  await expect(page.getByRole('button', { name: '7d', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})
