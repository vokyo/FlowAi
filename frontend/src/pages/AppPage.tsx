import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  closestCorners,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import {
  Activity,
  Archive,
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  Building2,
  CalendarDays,
  CheckCircle2,
  Circle,
  CircleDot,
  Clock3,
  Check,
  Copy,
  Flag,
  FolderKanban,
  GripVertical,
  LayoutList,
  Loader2,
  Maximize2,
  MessageSquare,
  MoreHorizontal,
  PanelRight,
  Pencil,
  Plus,
  Search,
  Send,
  RefreshCw,
  Tag,
  UserCircle,
  UserMinus,
  UserPlus,
  Users,
  Trash2,
  X,
} from 'lucide-react'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError } from '@/api/client'
import {
  getProjectAnalytics,
  type AnalyticsRangeDays,
} from '@/analytics/analytics-api'
import { getCurrentSession, type AuthUser, type AuthWorkspace } from '@/auth/auth-api'
import { getRefreshToken, saveAuthTokens } from '@/auth/token-storage'
import { Button } from '@/components/ui/button'
import {
  addProjectMember,
  createProjectLabel,
  createProjectWorkflowState,
  createIssue,
  createIssueComment,
  createProject,
  getProject,
  getProjectBoard,
  getIssue,
  listIssueActivities,
  listIssues,
  listProjectLabels,
  listProjectMembers,
  listProjectWorkflowStates,
  listProjects,
  listWorkspaceMembers,
  removeProjectMember,
  reorderIssues,
  reorderProjectWorkflowStates,
  updateIssue,
  updateProjectMember,
  updateProjectWorkflowState,
  type ActivityEvent,
  type BoardColumn,
  type IssueDetail,
  type IssuePriority,
  type IssueStatus,
  type IssueSummary,
  type Project,
  type ProjectBoard,
  type ProjectLabel,
  type ProjectMember,
  type ProjectWorkflowState,
  type ReorderIssuesRequest,
  type UpdateIssueRequest,
  type WorkflowStateCategory,
  type WorkspaceMember,
} from '@/work/work-api'
import {
  createWorkspace,
  createWorkspaceInvitation,
  listWorkspaceInvitations,
  listWorkspaces,
  reissueWorkspaceInvitation,
  revokeWorkspaceInvitation,
  switchWorkspace,
  type WorkspaceInvitation,
  type WorkspaceInvitationCreated,
  type WorkspaceRole,
} from '@/workspace/workspace-api'
import { WorkspaceSidebar } from '@/workspace/WorkspaceSidebar'
import {
  appendIssueToBoard,
  boardEmptyColumnLabel,
  buildOptimisticBoard,
  defaultWorkflowStateIdForStatus,
  filterProjectBoard,
  findBoardIssue,
  groupIssuesByWorkflowState,
  kanbanColumnId,
  type BoardIssueView,
  type IssueGroup,
  type IssueWorkflowFilter,
} from '@/work/board-utils'

const ProjectAnalyticsView = lazy(() =>
  import('@/analytics/ProjectAnalyticsView').then((module) => ({
    default: module.ProjectAnalyticsView,
  })),
)

const ISSUE_STATUSES = ['TODO', 'IN_PROGRESS', 'DONE', 'ARCHIVED'] as const
const WORKFLOW_STATE_CATEGORIES = ['TODO', 'IN_PROGRESS', 'DONE'] as const
const ISSUE_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const
const EMPTY_PROJECTS: Project[] = []
const EMPTY_ISSUES: IssueSummary[] = []
const EMPTY_PROJECT_LABELS: ProjectLabel[] = []
const EMPTY_PROJECT_MEMBERS: ProjectMember[] = []
const EMPTY_PROJECT_WORKFLOW_STATES: ProjectWorkflowState[] = []
const EMPTY_WORKSPACE_MEMBERS: WorkspaceMember[] = []
const EMPTY_WORKSPACES: AuthWorkspace[] = []
const EMPTY_WORKSPACE_INVITATIONS: WorkspaceInvitation[] = []
type InvitableWorkspaceRole = Exclude<WorkspaceRole, 'OWNER'>
const OWNER_INVITATION_ROLES: InvitableWorkspaceRole[] = ['ADMIN', 'MEMBER', 'GUEST']
const ADMIN_INVITATION_ROLES: InvitableWorkspaceRole[] = ['MEMBER', 'GUEST']
const DEFAULT_LABEL_COLOR = '#64748b'
const FORM_LIMITS = {
  projectName: 160,
  projectDescription: 2_000,
  workspaceName: 160,
  issueTitle: 240,
  issueDescription: 10_000,
  labelName: 60,
  workflowStateName: 60,
  commentBody: 4_000,
} as const

const STATUS_LABELS: Record<(typeof ISSUE_STATUSES)[number], string> = {
  TODO: 'Todo',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
  ARCHIVED: 'Archived',
}

const PRIORITY_LABELS: Record<(typeof ISSUE_PRIORITIES)[number], string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  URGENT: 'Urgent',
}

type AppPageProps = {
  onSignOut: () => void
  onSessionChanged: () => void
}

type AppRouteParams = {
  workspaceId?: string
  projectId?: string
  issueId?: string
}

type IssueViewMode = 'BOARD' | 'LIST'

type CreateDialog = 'project' | 'issue' | null

type CreateIssueDialogSeed = {
  title?: string
  assigneeUserId?: string | null
}

type KanbanReorder = ReorderIssuesRequest & {
  optimisticBoard: ProjectBoard
}

type ReorderIssueMutationVariables = {
  projectId: string
  request: ReorderIssuesRequest
  optimisticBoard: ProjectBoard
}

type QuickCreateIssueMutationVariables = {
  projectId: string
  title: string
  workflowStateId: string
  assigneeUserId?: string
}

type KanbanDragData = {
  type: 'column' | 'issue'
  workflowStateId: string
}

const requiredTrimmedString = (fieldName: string) =>
  z.string().refine((value) => value.trim().length > 0, {
    message: `${fieldName} is required.`,
  })

const optionalDateInputSchema = z.union([
  z.literal(''),
  z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.'),
])

const issuePriorityInputSchema = z.union([z.enum(ISSUE_PRIORITIES), z.literal('')])

const createProjectFormSchema = z.object({
  name: requiredTrimmedString('Name').max(FORM_LIMITS.projectName, 'Name is too long.'),
  description: z
    .string()
    .max(FORM_LIMITS.projectDescription, 'Description is too long.'),
})

const createWorkspaceFormSchema = z.object({
  name: requiredTrimmedString('Name').max(
    FORM_LIMITS.workspaceName,
    'Name is too long.',
  ),
})

const createWorkspaceInvitationFormSchema = z.object({
  email: z.string().min(1, 'Email is required.').email('Enter a valid email address.'),
  role: z.enum(['ADMIN', 'MEMBER', 'GUEST']),
})

const createIssueFormSchema = z.object({
  title: requiredTrimmedString('Title').max(FORM_LIMITS.issueTitle, 'Title is too long.'),
  description: z
    .string()
    .max(FORM_LIMITS.issueDescription, 'Description is too long.'),
  workflowStateId: z.string(),
  priority: issuePriorityInputSchema,
  labelIds: z.array(z.string()),
  assigneeUserId: z.string(),
  dueDate: optionalDateInputSchema,
})

const quickCreateIssueFormSchema = z.object({
  title: requiredTrimmedString('Title').max(FORM_LIMITS.issueTitle, 'Title is too long.'),
})

const createProjectLabelFormSchema = z.object({
  name: requiredTrimmedString('Label name').max(FORM_LIMITS.labelName, 'Label name is too long.'),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/, 'Choose a valid label color.'),
})

const createProjectWorkflowStateFormSchema = z.object({
  name: requiredTrimmedString('Status name').max(
    FORM_LIMITS.workflowStateName,
    'Status name is too long.',
  ),
  category: z.enum(WORKFLOW_STATE_CATEGORIES),
})

const updateProjectWorkflowStateFormSchema = createProjectWorkflowStateFormSchema

const addProjectMemberFormSchema = z.object({
  userId: requiredTrimmedString('Workspace member'),
})

const issueContentFormSchema = z.object({
  title: requiredTrimmedString('Title').max(FORM_LIMITS.issueTitle, 'Title is too long.'),
  description: z
    .string()
    .max(FORM_LIMITS.issueDescription, 'Description is too long.'),
})

const commentFormSchema = z.object({
  body: requiredTrimmedString('Comment').max(FORM_LIMITS.commentBody, 'Comment is too long.'),
})

type CreateProjectFormValues = z.infer<typeof createProjectFormSchema>
type CreateWorkspaceFormValues = z.infer<typeof createWorkspaceFormSchema>
type CreateWorkspaceInvitationFormValues = z.infer<
  typeof createWorkspaceInvitationFormSchema
>
type CreateIssueFormValues = z.infer<typeof createIssueFormSchema>
type QuickCreateIssueFormValues = z.infer<typeof quickCreateIssueFormSchema>
type CreateProjectLabelFormValues = z.infer<typeof createProjectLabelFormSchema>
type CreateProjectWorkflowStateFormValues = z.infer<typeof createProjectWorkflowStateFormSchema>
type UpdateProjectWorkflowStateFormValues = z.infer<typeof updateProjectWorkflowStateFormSchema>
type AddProjectMemberFormValues = z.infer<typeof addProjectMemberFormSchema>
type IssueContentFormValues = z.infer<typeof issueContentFormSchema>
type CommentFormValues = z.infer<typeof commentFormSchema>

export function AppPage({ onSignOut, onSessionChanged }: AppPageProps) {
  const queryClient = useQueryClient()
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const {
    workspaceId: routeWorkspaceId,
    projectId: routeProjectId,
    issueId: routeIssueId,
  } = useParams<AppRouteParams>()
  const [activeCreateDialog, setActiveCreateDialog] = useState<CreateDialog>(null)
  const [areProjectsOpen, setAreProjectsOpen] = useState(true)
  const [createIssueDefaultWorkflowStateId, setCreateIssueDefaultWorkflowStateId] = useState('')
  const [createIssueDefaultTitle, setCreateIssueDefaultTitle] = useState('')
  const [createIssueDefaultAssigneeUserId, setCreateIssueDefaultAssigneeUserId] = useState('')
  const [issueSearchQuery, setIssueSearchQuery] = useState('')
  const [issueWorkflowFilter, setIssueWorkflowFilter] = useState<IssueWorkflowFilter>('ACTIVE')
  const [issuePriorityFilter, setIssuePriorityFilter] = useState<IssuePriority | ''>('')
  const [issueLabelFilter, setIssueLabelFilter] = useState('')
  const [issueAssigneeFilter, setIssueAssigneeFilter] = useState('')
  const [isProjectMembersDialogOpen, setIsProjectMembersDialogOpen] = useState(false)
  const [isProjectWorkflowDialogOpen, setIsProjectWorkflowDialogOpen] = useState(false)
  const [isWorkspaceMenuOpen, setIsWorkspaceMenuOpen] = useState(false)
  const [isCreateWorkspaceDialogOpen, setIsCreateWorkspaceDialogOpen] = useState(false)
  const [isWorkspaceInvitationsDialogOpen, setIsWorkspaceInvitationsDialogOpen] = useState(false)
  const [latestInvitationLink, setLatestInvitationLink] = useState<string | null>(null)
  const isAnalyticsRoute = isProjectAnalyticsPath(location.pathname)
  const issueViewMode = issueViewModeFromSearchParams(searchParams)
  const boardIssueView = boardIssueViewFromSearchParams(searchParams)
  const analyticsRangeDays = analyticsRangeFromSearchParams(searchParams)
  const normalizedWorkViewSearchParams = issueViewSearchParams(searchParams)
  const normalizedAppSearchParams = normalizeAppSearchParams(
    searchParams,
    isAnalyticsRoute,
  )
  const rawSearchString = searchParams.toString()
  const normalizedSearchString = normalizedAppSearchParams.toString()

  useEffect(() => {
    if (rawSearchString !== normalizedSearchString) {
      setSearchParams(new URLSearchParams(normalizedSearchString), { replace: true })
    }
  }, [normalizedSearchString, rawSearchString, setSearchParams])

  const currentSessionQuery = useQuery({
    queryKey: ['current-session'],
    queryFn: getCurrentSession,
    retry: false,
  })

  const sessionWorkspace = currentSessionQuery.data?.workspace ?? null
  const currentUser = currentSessionQuery.data?.user ?? null
  const currentWorkspace = sessionWorkspace
  const currentWorkspaceId = currentWorkspace?.id ?? null
  const canManageWorkspaceInvitations =
    currentWorkspace?.role === 'OWNER' || currentWorkspace?.role === 'ADMIN'
  const routeMatchesCurrentWorkspace =
    !routeWorkspaceId || !currentWorkspaceId || routeWorkspaceId === currentWorkspaceId
  const canLoadCurrentWorkspace = Boolean(currentWorkspaceId && routeMatchesCurrentWorkspace)

  const workspacesQuery = useQuery({
    queryKey: ['workspaces'],
    queryFn: listWorkspaces,
    enabled: currentSessionQuery.isSuccess,
    retry: false,
  })

  const workspaces = workspacesQuery.data ?? EMPTY_WORKSPACES

  const projectsQuery = useQuery({
    queryKey: ['projects', currentWorkspaceId],
    queryFn: () => listProjects(),
    enabled: canLoadCurrentWorkspace,
    retry: false,
  })

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

  const selectedProject = selectedProjectQuery.data ?? selectedProjectFromList

  const projectMembersQuery = useQuery({
    queryKey: ['project-members', selectedProjectId],
    queryFn: () => listProjectMembers(selectedProjectId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    retry: false,
  })

  const projectLabelsQuery = useQuery({
    queryKey: ['project-labels', selectedProjectId],
    queryFn: () => listProjectLabels(selectedProjectId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    retry: false,
  })

  const projectWorkflowStatesQuery = useQuery({
    queryKey: ['project-workflow-states', selectedProjectId],
    queryFn: () => listProjectWorkflowStates(selectedProjectId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId),
    retry: false,
  })

  const projectBoardQuery = useQuery({
    queryKey: ['project-board', currentWorkspaceId, selectedProjectId],
    queryFn: () => getProjectBoard(selectedProjectId ?? ''),
    enabled: Boolean(
      canLoadCurrentWorkspace &&
      selectedProjectId &&
      issueViewMode === 'BOARD' &&
      !isAnalyticsRoute,
    ),
    retry: false,
  })

  const projectAnalyticsQuery = useQuery({
    queryKey: [
      'project-analytics',
      currentWorkspaceId,
      selectedProjectId,
      analyticsRangeDays,
    ],
    queryFn: () => getProjectAnalytics(selectedProjectId ?? '', analyticsRangeDays),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId && isAnalyticsRoute),
    retry: false,
  })

  const workspaceMembersQuery = useQuery({
    queryKey: ['workspace-members', currentWorkspaceId],
    queryFn: listWorkspaceMembers,
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId && isProjectMembersDialogOpen),
    retry: false,
  })

  const workspaceInvitationsQuery = useQuery({
    queryKey: ['workspace-invitations', currentWorkspaceId],
    queryFn: listWorkspaceInvitations,
    enabled: Boolean(
      canLoadCurrentWorkspace &&
      canManageWorkspaceInvitations &&
      isWorkspaceInvitationsDialogOpen
    ),
    retry: false,
  })

  const projectMembers = projectMembersQuery.data ?? EMPTY_PROJECT_MEMBERS
  const projectLabels = projectLabelsQuery.data ?? EMPTY_PROJECT_LABELS
  const projectWorkflowStates = projectWorkflowStatesQuery.data ?? EMPTY_PROJECT_WORKFLOW_STATES
  const workspaceMembers = workspaceMembersQuery.data ?? EMPTY_WORKSPACE_MEMBERS
  const workspaceInvitations =
    workspaceInvitationsQuery.data ?? EMPTY_WORKSPACE_INVITATIONS
  const activeProjectMembers = useMemo(
    () => projectMembers.filter((member) => member.status === 'ACTIVE'),
    [projectMembers],
  )
  const currentProjectMember = projectMembers.find(
    (member) => member.userId === currentUser?.id && member.status === 'ACTIVE',
  )
  const canManageProjectMembers = currentProjectMember?.role === 'OWNER'
  const activeProjectMemberUserIds = useMemo(
    () =>
      new Set(
        projectMembers
          .filter((member) => member.status === 'ACTIVE')
          .map((member) => member.userId),
      ),
    [projectMembers],
  )
  const addableWorkspaceMembers = useMemo(
    () =>
      workspaceMembers.filter(
        (member) =>
          member.status === 'ACTIVE' && !activeProjectMemberUserIds.has(member.userId),
      ),
    [activeProjectMemberUserIds, workspaceMembers],
  )
  const normalizedIssueSearchQuery = issueSearchQuery.trim()
  const issueStatusQuery = issueWorkflowFilter === 'ARCHIVED' ? 'ARCHIVED' : undefined
  const issueWorkflowStateQuery =
    issueWorkflowFilter === 'ACTIVE' || issueWorkflowFilter === 'ARCHIVED'
      ? undefined
      : issueWorkflowFilter

  const issuesQuery = useQuery({
    queryKey: [
      'issues',
      currentWorkspaceId,
      selectedProjectId,
      issueWorkflowFilter,
      issuePriorityFilter,
      issueLabelFilter,
      issueAssigneeFilter,
      normalizedIssueSearchQuery,
    ],
    queryFn: () =>
      listIssues(selectedProjectId ?? '', {
        status: issueStatusQuery,
        workflowStateId: issueWorkflowStateQuery,
        priority: issuePriorityFilter || undefined,
        labelId: issueLabelFilter || undefined,
        assigneeUserId: issueAssigneeFilter || undefined,
        q: normalizedIssueSearchQuery || undefined,
      }),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProjectId && !isAnalyticsRoute),
    retry: false,
  })

  const issues = issuesQuery.data ?? EMPTY_ISSUES
  const projectBoard = projectBoardQuery.data ?? null
  const issueGroups = useMemo(
    () => groupIssuesByWorkflowState(issues, projectWorkflowStates, issueWorkflowFilter),
    [issueWorkflowFilter, issues, projectWorkflowStates],
  )
  const hasIssueFilters = Boolean(
    normalizedIssueSearchQuery ||
      issueWorkflowFilter !== 'ACTIVE' ||
      issuePriorityFilter ||
      issueLabelFilter ||
      issueAssigneeFilter,
  )
  const selectedIssueSummary = routeIssueId
    ? issues.find((issue) => issue.id === routeIssueId) ?? null
    : null

  const issueDetailQuery = useQuery({
    queryKey: ['issue', routeIssueId],
    queryFn: () => getIssue(routeIssueId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProject && routeIssueId),
    retry: false,
  })

  const activitiesQuery = useQuery({
    queryKey: ['issue-activities', routeIssueId],
    queryFn: () => listIssueActivities(routeIssueId ?? ''),
    enabled: Boolean(canLoadCurrentWorkspace && selectedProject && routeIssueId),
    retry: false,
  })

  useEffect(() => {
    if (!currentWorkspaceId || !canLoadCurrentWorkspace || !projectsQuery.isSuccess) {
      return
    }

    if (projects.length === 0) {
      if (routeProjectId || routeIssueId) {
        navigate(pathWithSearchParams('/app', normalizedSearchString), { replace: true })
      }
      return
    }

    const hasRouteProject = Boolean(
      routeProjectId && projects.some((project) => project.id === routeProjectId),
    )

    if (!routeProjectId || !hasRouteProject) {
      const firstProjectPath = isAnalyticsRoute
        ? projectAnalyticsPath(currentWorkspaceId, projects[0].id)
        : projectPath(currentWorkspaceId, projects[0].id)
      navigate(
        pathWithSearchParams(
          firstProjectPath,
          normalizedSearchString,
        ),
        { replace: true },
      )
    }
  }, [
    canLoadCurrentWorkspace,
    currentWorkspaceId,
    isAnalyticsRoute,
    navigate,
    normalizedSearchString,
    projects,
    projectsQuery.isSuccess,
    routeIssueId,
    routeProjectId,
  ])

  async function applyWorkspaceSession(response: {
    accessToken: string
    refreshToken: string
  }) {
    saveAuthTokens(response)
    await queryClient.cancelQueries()
    queryClient.clear()
    onSessionChanged()
    navigate(
      pathWithSearchParams('/app', normalizedWorkViewSearchParams),
      { replace: true },
    )
  }

  const switchWorkspaceMutation = useMutation({
    mutationFn: (workspaceId: string) =>
      switchWorkspace(workspaceId, requireRefreshToken()),
    onSuccess: applyWorkspaceSession,
  })

  const createWorkspaceMutation = useMutation({
    mutationFn: async (values: CreateWorkspaceFormValues) => {
      const workspace = await createWorkspace({ name: values.name.trim() })
      const refreshToken = requireRefreshToken()
      return switchWorkspace(workspace.id, refreshToken)
    },
    onSuccess: async (response) => {
      setIsCreateWorkspaceDialogOpen(false)
      await applyWorkspaceSession(response)
    },
    onError: () => {
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
    },
  })

  const createWorkspaceInvitationMutation = useMutation({
    mutationFn: (values: CreateWorkspaceInvitationFormValues) =>
      createWorkspaceInvitation({
        email: values.email.trim(),
        role: values.role,
      }),
    onSuccess: async (invitation) => {
      setLatestInvitationLink(invitationUrl(invitation))
      await queryClient.invalidateQueries({
        queryKey: ['workspace-invitations', currentWorkspaceId],
      })
    },
  })

  const reissueWorkspaceInvitationMutation = useMutation({
    mutationFn: reissueWorkspaceInvitation,
    onSuccess: async (invitation) => {
      setLatestInvitationLink(invitationUrl(invitation))
      await queryClient.invalidateQueries({
        queryKey: ['workspace-invitations', currentWorkspaceId],
      })
    },
  })

  const revokeWorkspaceInvitationMutation = useMutation({
    mutationFn: revokeWorkspaceInvitation,
    onSuccess: async () => {
      setLatestInvitationLink(null)
      await queryClient.invalidateQueries({
        queryKey: ['workspace-invitations', currentWorkspaceId],
      })
    },
  })

  const createProjectMutation = useMutation({
    mutationFn: createProject,
    onSuccess: async (project) => {
      setActiveCreateDialog(null)
      await queryClient.invalidateQueries({ queryKey: ['projects', currentWorkspaceId] })
      if (currentWorkspaceId) {
        navigate(
          pathWithSearchParams(
            projectPath(currentWorkspaceId, project.id),
            normalizedWorkViewSearchParams,
          ),
        )
      }
    },
  })

  const createIssueMutation = useMutation({
    mutationFn: createIssue,
    onSuccess: async (issue) => {
      setActiveCreateDialog(null)
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, issue.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, issue.projectId],
        }),
        invalidateProjectAnalytics(issue.projectId),
      ])
      if (currentWorkspaceId) {
        navigate(
          pathWithSearchParams(
            issuePath(currentWorkspaceId, issue.projectId, issue.id),
            normalizedWorkViewSearchParams,
          ),
        )
      }
    },
  })

  const quickCreateIssueMutation = useMutation({
    mutationFn: ({
      projectId,
      title,
      workflowStateId,
      assigneeUserId,
    }: QuickCreateIssueMutationVariables) =>
      createIssue({
        projectId,
        title,
        workflowStateId,
        assigneeUserId,
      }),
    onSuccess: (issue) => {
      queryClient.setQueryData<ProjectBoard>(
        ['project-board', currentWorkspaceId, issue.projectId],
        (currentBoard) => appendIssueToBoard(currentBoard, issue),
      )
      void Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, issue.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, issue.projectId],
        }),
        invalidateProjectAnalytics(issue.projectId),
      ])
    },
  })

  const createCommentMutation = useMutation({
    mutationFn: ({ issueId, body }: { issueId: string; projectId: string; body: string }) =>
      createIssueComment(issueId, { body }),
    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issue', variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-activities', variables.issueId] }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, variables.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, variables.projectId],
        }),
      ])
    },
  })

  const updateIssueMutation = useMutation({
    mutationFn: ({
      issueId,
      request,
    }: {
      issueId: string
      projectId: string
      request: UpdateIssueRequest
    }) => updateIssue(issueId, request),
    onSuccess: async (issue, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issue', variables.issueId] }),
        queryClient.invalidateQueries({ queryKey: ['issue-activities', variables.issueId] }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, issue.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, issue.projectId],
        }),
        invalidateProjectAnalytics(issue.projectId),
      ])
    },
  })

  const reorderIssueMutation = useMutation({
    mutationFn: ({ request }: ReorderIssueMutationVariables) => reorderIssues(request),
    onMutate: async (variables) => {
      const queryKey = ['project-board', currentWorkspaceId, variables.projectId]
      await queryClient.cancelQueries({ queryKey })
      const previousBoard = queryClient.getQueryData<ProjectBoard>(queryKey)
      queryClient.setQueryData(queryKey, variables.optimisticBoard)
      return { previousBoard, queryKey }
    },
    onError: (_, __, context) => {
      if (context?.previousBoard) {
        queryClient.setQueryData(context.queryKey, context.previousBoard)
      }
    },
    onSuccess: (board, variables) => {
      queryClient.setQueryData(
        ['project-board', currentWorkspaceId, variables.projectId],
        board,
      )
    },
    onSettled: async (_, __, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, variables.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, variables.projectId],
        }),
        queryClient.invalidateQueries({ queryKey: ['issue', variables.request.issueId] }),
        queryClient.invalidateQueries({
          queryKey: ['issue-activities', variables.request.issueId],
        }),
        invalidateProjectAnalytics(variables.projectId),
      ])
    },
  })

  const createProjectLabelMutation = useMutation({
    mutationFn: ({
      projectId,
      name,
      color,
    }: {
      projectId: string
      name: string
      color: string
    }) => createProjectLabel(projectId, { name, color }),
    onSuccess: async (label) => {
      await queryClient.invalidateQueries({ queryKey: ['project-labels', label.projectId] })
    },
  })

  const createProjectWorkflowStateMutation = useMutation({
    mutationFn: ({
      projectId,
      name,
      category,
    }: {
      projectId: string
      name: string
      category: WorkflowStateCategory
    }) => createProjectWorkflowState(projectId, { name, category }),
    onSuccess: async (workflowState) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['project-workflow-states', workflowState.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, workflowState.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, workflowState.projectId],
        }),
      ])
    },
  })

  const updateProjectWorkflowStateMutation = useMutation({
    mutationFn: ({
      projectId,
      workflowStateId,
      values,
    }: {
      projectId: string
      workflowStateId: string
      values: UpdateProjectWorkflowStateFormValues
    }) => updateProjectWorkflowState(projectId, workflowStateId, values),
    onSuccess: async (workflowState) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['project-workflow-states', workflowState.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, workflowState.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, workflowState.projectId],
        }),
        queryClient.invalidateQueries({ queryKey: ['issue'] }),
        invalidateProjectAnalytics(workflowState.projectId),
      ])
    },
  })

  const reorderProjectWorkflowStatesMutation = useMutation({
    mutationFn: ({
      projectId,
      workflowStateIds,
    }: {
      projectId: string
      workflowStateIds: string[]
    }) => reorderProjectWorkflowStates(projectId, { workflowStateIds }),
    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['project-workflow-states', variables.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['issues', currentWorkspaceId, variables.projectId],
        }),
        queryClient.invalidateQueries({
          queryKey: ['project-board', currentWorkspaceId, variables.projectId],
        }),
        queryClient.invalidateQueries({ queryKey: ['issue'] }),
      ])
    },
  })

  const addProjectMemberMutation = useMutation({
    mutationFn: ({ projectId, userId }: { projectId: string; userId: string }) =>
      addProjectMember(projectId, { userId, role: 'MEMBER' }),
    onSuccess: async (_, variables) => invalidateProjectMemberQueries(variables.projectId),
  })

  const updateProjectMemberMutation = useMutation({
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

  const removeProjectMemberMutation = useMutation({
    mutationFn: ({
      projectId,
      memberId,
    }: {
      projectId: string
      memberId: string
      userId: string
    }) => removeProjectMember(projectId, memberId),
    onSuccess: async (_, variables) => {
      if (variables.userId === currentUser?.id) {
        setIsProjectMembersDialogOpen(false)
      }

      await invalidateProjectMemberQueries(variables.projectId)
    },
  })

  function invalidateProjectMemberQueries(projectId: string) {
    return Promise.all([
      queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['project-members', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['projects', currentWorkspaceId] }),
      queryClient.invalidateQueries({
        queryKey: ['issues', currentWorkspaceId, projectId],
      }),
      queryClient.invalidateQueries({ queryKey: ['issue'] }),
    ])
  }

  function invalidateProjectAnalytics(projectId: string) {
    return queryClient.invalidateQueries({
      queryKey: ['project-analytics', currentWorkspaceId, projectId],
    })
  }

  function resetProjectMemberMutations() {
    addProjectMemberMutation.reset()
    updateProjectMemberMutation.reset()
    removeProjectMemberMutation.reset()
  }

  function openCreateProjectDialog() {
    createProjectMutation.reset()
    setActiveCreateDialog('project')
  }

  function handleWorkspaceSelect(workspaceId: string) {
    setIsWorkspaceMenuOpen(false)
    if (workspaceId === currentWorkspaceId) {
      return
    }
    switchWorkspaceMutation.reset()
    switchWorkspaceMutation.mutate(workspaceId)
  }

  function openCreateWorkspaceDialog() {
    setIsWorkspaceMenuOpen(false)
    createWorkspaceMutation.reset()
    setIsCreateWorkspaceDialogOpen(true)
  }

  function closeCreateWorkspaceDialog() {
    if (!createWorkspaceMutation.isPending) {
      setIsCreateWorkspaceDialogOpen(false)
    }
  }

  function openWorkspaceInvitationsDialog() {
    setIsWorkspaceMenuOpen(false)
    setLatestInvitationLink(null)
    createWorkspaceInvitationMutation.reset()
    reissueWorkspaceInvitationMutation.reset()
    revokeWorkspaceInvitationMutation.reset()
    setIsWorkspaceInvitationsDialogOpen(true)
  }

  function closeWorkspaceInvitationsDialog() {
    if (
      createWorkspaceInvitationMutation.isPending ||
      reissueWorkspaceInvitationMutation.isPending ||
      revokeWorkspaceInvitationMutation.isPending
    ) {
      return
    }
    setIsWorkspaceInvitationsDialogOpen(false)
    setLatestInvitationLink(null)
  }

  function openCreateIssueDialog(
    workflowState?: ProjectWorkflowState | null,
    seed: CreateIssueDialogSeed = {},
  ) {
    createIssueMutation.reset()
    setCreateIssueDefaultWorkflowStateId(
      workflowState?.id ?? defaultWorkflowStateIdForStatus(projectWorkflowStates, 'TODO'),
    )
    setCreateIssueDefaultTitle(seed.title ?? '')
    setCreateIssueDefaultAssigneeUserId(seed.assigneeUserId ?? '')
    createProjectLabelMutation.reset()
    setActiveCreateDialog('issue')
  }

  function closeCreateDialog() {
    if (
      createProjectMutation.isPending ||
      createIssueMutation.isPending ||
      createProjectLabelMutation.isPending
    ) {
      return
    }

    setActiveCreateDialog(null)
  }

  function openProjectMembersDialog() {
    resetProjectMemberMutations()
    setIsProjectMembersDialogOpen(true)
  }

  function closeProjectMembersDialog() {
    if (
      addProjectMemberMutation.isPending ||
      updateProjectMemberMutation.isPending ||
      removeProjectMemberMutation.isPending
    ) {
      return
    }

    setIsProjectMembersDialogOpen(false)
  }

  function openProjectWorkflowDialog() {
    createProjectWorkflowStateMutation.reset()
    updateProjectWorkflowStateMutation.reset()
    reorderProjectWorkflowStatesMutation.reset()
    setIsProjectWorkflowDialogOpen(true)
  }

  function closeProjectWorkflowDialog() {
    if (
      createProjectWorkflowStateMutation.isPending ||
      updateProjectWorkflowStateMutation.isPending ||
      reorderProjectWorkflowStatesMutation.isPending
    ) {
      return
    }

    setIsProjectWorkflowDialogOpen(false)
  }

  function clearIssueFilters() {
    setIssueSearchQuery('')
    setIssueWorkflowFilter('ACTIVE')
    setIssuePriorityFilter('')
    setIssueLabelFilter('')
    setIssueAssigneeFilter('')
  }

  function handleIssueViewModeChange(nextLayout: IssueViewMode) {
    setSearchParams(
      workViewSearchParams(searchParams, nextLayout, boardIssueView),
    )
  }

  function handleBoardIssueViewChange(nextView: BoardIssueView) {
    setSearchParams(
      workViewSearchParams(searchParams, issueViewMode, nextView),
    )
  }

  function handleSidebarViewSelect(nextView: BoardIssueView) {
    if (!currentWorkspaceId || !selectedProjectId) {
      return
    }

    const nextSearchParams = workViewSearchParams(searchParams, 'BOARD', nextView)
    navigate(
      pathWithSearchParams(
        projectPath(currentWorkspaceId, selectedProjectId),
        nextSearchParams,
      ),
    )
  }

  function handleAnalyticsSelect() {
    if (!currentWorkspaceId || !selectedProjectId) {
      return
    }

    navigate(
      pathWithSearchParams(
        projectAnalyticsPath(currentWorkspaceId, selectedProjectId),
        analyticsSearchParams(searchParams, analyticsRangeDays),
      ),
    )
  }

  function handleAnalyticsRangeChange(nextRangeDays: AnalyticsRangeDays) {
    setSearchParams(analyticsSearchParams(searchParams, nextRangeDays))
  }

  function handleProjectSelect(projectId: string) {
    if (!currentWorkspaceId) {
      return
    }

    const nextPath = isAnalyticsRoute
      ? projectAnalyticsPath(currentWorkspaceId, projectId)
      : projectPath(currentWorkspaceId, projectId)
    const nextSearchParams = isAnalyticsRoute
      ? normalizedAppSearchParams
      : normalizedWorkViewSearchParams

    navigate(pathWithSearchParams(nextPath, nextSearchParams))
  }

  function handleIssueSelect(issueId: string) {
    if (!currentWorkspaceId || !selectedProjectId) {
      return
    }

    navigate(
      pathWithSearchParams(
        issuePath(currentWorkspaceId, selectedProjectId, issueId),
        normalizedWorkViewSearchParams,
      ),
    )
  }

  async function handleReorderIssue({
    optimisticBoard,
    ...request
  }: KanbanReorder) {
    if (!selectedProjectId) {
      return
    }

    reorderIssueMutation.reset()
    await reorderIssueMutation.mutateAsync({
      projectId: selectedProjectId,
      request,
      optimisticBoard,
    })
  }

  async function handleQuickCreateIssue({
    title,
    workflowStateId,
    assigneeUserId,
  }: Omit<QuickCreateIssueMutationVariables, 'projectId'>) {
    const trimmedTitle = title.trim()
    if (!selectedProjectId || !trimmedTitle) {
      return null
    }

    quickCreateIssueMutation.reset()
    return quickCreateIssueMutation.mutateAsync({
      projectId: selectedProjectId,
      title: trimmedTitle,
      workflowStateId,
      assigneeUserId,
    })
  }

  function handleCreateProject(values: CreateProjectFormValues) {
    const name = values.name.trim()
    if (!name || !canLoadCurrentWorkspace) {
      return
    }

    createProjectMutation.mutate({
      name,
      description: values.description.trim() || undefined,
    })
  }

  function handleCreateIssue(values: CreateIssueFormValues) {
    const title = values.title.trim()
    if (!selectedProjectId || !title) {
      return
    }

    createIssueMutation.mutate({
      projectId: selectedProjectId,
      title,
      description: values.description.trim() || undefined,
      workflowStateId: values.workflowStateId || undefined,
      priority: values.priority || undefined,
      labelIds: values.labelIds,
      assigneeUserId: values.assigneeUserId || undefined,
      dueDate: values.dueDate || undefined,
    })
  }

  async function handleCreateIssueLabel(values: CreateProjectLabelFormValues) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) {
      return null
    }

    createProjectLabelMutation.reset()
    return createProjectLabelMutation.mutateAsync({
      projectId: selectedProjectId,
      name,
      color: values.color,
    })
  }

  async function handleCreateProjectWorkflowState(values: CreateProjectWorkflowStateFormValues) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) {
      return null
    }

    createProjectWorkflowStateMutation.reset()
    return createProjectWorkflowStateMutation.mutateAsync({
      projectId: selectedProjectId,
      name,
      category: values.category,
    })
  }

  async function handleUpdateProjectWorkflowState(
    workflowStateId: string,
    values: UpdateProjectWorkflowStateFormValues,
  ) {
    const name = values.name.trim()
    if (!selectedProjectId || !name) {
      return null
    }

    updateProjectWorkflowStateMutation.reset()
    return updateProjectWorkflowStateMutation.mutateAsync({
      projectId: selectedProjectId,
      workflowStateId,
      values: {
        name,
        category: values.category,
      },
    })
  }

  async function handleReorderProjectWorkflowState(workflowStateId: string, direction: -1 | 1) {
    if (!selectedProjectId) {
      return
    }

    const currentIndex = projectWorkflowStates.findIndex(
      (workflowState) => workflowState.id === workflowStateId,
    )
    const nextIndex = currentIndex + direction
    if (
      currentIndex < 0 ||
      nextIndex < 0 ||
      nextIndex >= projectWorkflowStates.length
    ) {
      return
    }

    const workflowStateIds = projectWorkflowStates.map((workflowState) => workflowState.id)
    const nextWorkflowStateId = workflowStateIds[nextIndex]
    workflowStateIds[nextIndex] = workflowStateIds[currentIndex]
    workflowStateIds[currentIndex] = nextWorkflowStateId

    reorderProjectWorkflowStatesMutation.reset()
    await reorderProjectWorkflowStatesMutation.mutateAsync({
      projectId: selectedProjectId,
      workflowStateIds,
    })
  }

  async function handleAddProjectMember(values: AddProjectMemberFormValues) {
    const userId = values.userId.trim()
    if (!selectedProjectId || !userId) {
      return
    }

    resetProjectMemberMutations()
    await addProjectMemberMutation.mutateAsync({
      projectId: selectedProjectId,
      userId,
    })
  }

  async function handleUpdateProjectMemberRole(
    memberId: string,
    role: 'OWNER' | 'MEMBER',
  ) {
    if (!selectedProjectId) {
      return
    }

    resetProjectMemberMutations()
    await updateProjectMemberMutation.mutateAsync({
      projectId: selectedProjectId,
      memberId,
      role,
    })
  }

  async function handleRemoveProjectMember(member: ProjectMember) {
    if (!selectedProjectId) {
      return
    }

    const memberName = member.displayName || member.email
    const isRemovingSelf = member.userId === currentUser?.id
    const confirmed = window.confirm(
      isRemovingSelf
        ? 'Remove yourself from this project? You will lose access immediately.'
        : `Remove ${memberName} from this project?`,
    )
    if (!confirmed) {
      return
    }

    resetProjectMemberMutations()
    await removeProjectMemberMutation.mutateAsync({
      projectId: selectedProjectId,
      memberId: member.id,
      userId: member.userId,
    })
  }

  async function handleCreateComment(values: CommentFormValues) {
    const body = values.body.trim()
    if (!routeIssueId || !selectedProjectId || !body) {
      return
    }

    await createCommentMutation.mutateAsync({
      issueId: routeIssueId,
      projectId: selectedProjectId,
      body,
    })
  }

  async function handleUpdateIssue(request: UpdateIssueRequest) {
    if (!routeIssueId || !selectedProjectId) {
      return
    }

    updateIssueMutation.reset()
    await updateIssueMutation.mutateAsync({
      issueId: routeIssueId,
      projectId: selectedProjectId,
      request,
    })
  }

  async function handleArchiveIssue() {
    if (!routeIssueId) {
      return
    }

    const confirmed = window.confirm(
      'Archive this issue? It will be hidden from the active issue list.',
    )
    if (!confirmed) {
      return
    }

    try {
      await handleUpdateIssue({ status: 'ARCHIVED' })
    } catch {
      // The shared update error is rendered in the detail panel.
    }
  }

  const activeIssue = issueDetailQuery.data ?? selectedIssueSummary
  const activities = activitiesQuery.data ?? []
  const isIssueDetailRoute = Boolean(routeIssueId)

  return (
    <main className="app-shell">
      <WorkspaceSidebar
        currentWorkspace={currentWorkspace}
        workspaces={workspaces}
        isLoadingWorkspace={currentSessionQuery.isLoading}
        isLoadingWorkspaces={workspacesQuery.isLoading}
        workspacesError={workspacesQuery.error ?? switchWorkspaceMutation.error}
        isWorkspaceMenuOpen={isWorkspaceMenuOpen}
        isSwitchingWorkspace={switchWorkspaceMutation.isPending}
        onToggleWorkspaceMenu={() => setIsWorkspaceMenuOpen((isOpen) => !isOpen)}
        onCloseWorkspaceMenu={() => setIsWorkspaceMenuOpen(false)}
        onWorkspaceSelect={handleWorkspaceSelect}
        onOpenCreateWorkspace={openCreateWorkspaceDialog}
        onOpenWorkspaceInvitations={openWorkspaceInvitationsDialog}
        canManageWorkspaceInvitations={canManageWorkspaceInvitations}
        projects={projects}
        selectedProjectId={selectedProjectId}
        isAnalyticsRoute={isAnalyticsRoute}
        issueViewMode={issueViewMode}
        boardIssueView={boardIssueView}
        isLoadingProjects={projectsQuery.isLoading}
        projectsError={projectsQuery.error}
        areProjectsOpen={areProjectsOpen}
        onToggleProjects={() => setAreProjectsOpen((isOpen) => !isOpen)}
        onOpenCreateProject={openCreateProjectDialog}
        canCreateProject={canLoadCurrentWorkspace && currentWorkspace?.role !== 'GUEST'}
        canSelectViews={Boolean(selectedProjectId)}
        onViewSelect={handleSidebarViewSelect}
        onAnalyticsSelect={handleAnalyticsSelect}
        onProjectSelect={handleProjectSelect}
        onSignOut={onSignOut}
      />

      <section className="app-main">
        {!routeMatchesCurrentWorkspace ? (
          <WorkspaceMismatchState
            currentWorkspace={currentWorkspace}
            routeWorkspaceId={routeWorkspaceId}
          />
        ) : isIssueDetailRoute ? (
          <IssueDetailView
            issue={activeIssue}
            issueDetail={issueDetailQuery.data}
            selectedProject={selectedProject}
            projectMembers={activeProjectMembers}
            projectLabels={projectLabels}
            projectWorkflowStates={projectWorkflowStates}
            currentWorkspace={currentWorkspace}
            currentUser={currentUser}
            activities={activities}
            isLoadingIssue={issueDetailQuery.isLoading}
            issueError={issueDetailQuery.error}
            isLoadingActivities={activitiesQuery.isLoading}
            activitiesError={activitiesQuery.error}
            onSubmitComment={handleCreateComment}
            isSubmittingComment={createCommentMutation.isPending}
            commentError={createCommentMutation.error}
            onBackToProject={() => {
              if (currentWorkspaceId && selectedProjectId) {
                navigate(
                  pathWithSearchParams(
                    projectPath(currentWorkspaceId, selectedProjectId),
                    normalizedWorkViewSearchParams,
                  ),
                )
              }
            }}
            onUpdateIssue={handleUpdateIssue}
            onArchiveIssue={handleArchiveIssue}
            isUpdatingIssue={updateIssueMutation.isPending}
            updateIssueError={updateIssueMutation.error}
            onResetUpdateIssueError={() => updateIssueMutation.reset()}
          />
        ) : isAnalyticsRoute ? (
          <Suspense fallback={<BoardLoadingState workflowStates={projectWorkflowStates} />}>
            <ProjectAnalyticsView
              project={selectedProject}
              overview={projectAnalyticsQuery.data ?? null}
              rangeDays={analyticsRangeDays}
              isLoading={projectAnalyticsQuery.isLoading}
              error={projectAnalyticsQuery.error}
              onRangeChange={handleAnalyticsRangeChange}
            />
          </Suspense>
        ) : (
          <ProjectIssuesView
            currentWorkspace={currentWorkspace}
            currentUser={currentUser}
            selectedProject={selectedProject}
            projectMembers={projectMembers}
            projectLabels={projectLabels}
            projectWorkflowStates={projectWorkflowStates}
            projectBoard={projectBoard}
            issues={issues}
            issueGroups={issueGroups}
            issueViewMode={issueViewMode}
            boardIssueView={boardIssueView}
            selectedIssueId={routeIssueId ?? null}
            isLoadingIssues={issuesQuery.isLoading}
            issuesError={issuesQuery.error}
            isLoadingProjectBoard={projectBoardQuery.isLoading}
            projectBoardError={projectBoardQuery.error}
            reorderIssueError={reorderIssueMutation.error}
            isReorderingIssue={reorderIssueMutation.isPending}
            quickCreateIssueError={quickCreateIssueMutation.error}
            isQuickCreatingIssue={quickCreateIssueMutation.isPending}
            canUseQuickCreateShortcut={
              activeCreateDialog === null &&
              !isProjectMembersDialogOpen &&
              !isProjectWorkflowDialogOpen &&
              !isCreateWorkspaceDialogOpen &&
              !isWorkspaceInvitationsDialogOpen
            }
            isLoadingProjectMembers={projectMembersQuery.isLoading}
            isLoadingProjects={projectsQuery.isLoading}
            issueSearchQuery={issueSearchQuery}
            issueWorkflowFilter={issueWorkflowFilter}
            issuePriorityFilter={issuePriorityFilter}
            issueLabelFilter={issueLabelFilter}
            issueAssigneeFilter={issueAssigneeFilter}
            hasIssueFilters={hasIssueFilters}
            onIssueSearchQueryChange={setIssueSearchQuery}
            onIssueWorkflowFilterChange={setIssueWorkflowFilter}
            onIssuePriorityFilterChange={setIssuePriorityFilter}
            onIssueLabelFilterChange={setIssueLabelFilter}
            onIssueAssigneeFilterChange={setIssueAssigneeFilter}
            onIssueViewModeChange={handleIssueViewModeChange}
            onBoardIssueViewChange={handleBoardIssueViewChange}
            onClearIssueFilters={clearIssueFilters}
            onOpenProjectMembers={openProjectMembersDialog}
            onOpenProjectWorkflow={openProjectWorkflowDialog}
            onOpenCreateIssue={openCreateIssueDialog}
            onIssueSelect={handleIssueSelect}
            onReorderIssue={handleReorderIssue}
            onQuickCreateIssue={handleQuickCreateIssue}
            onResetQuickCreateIssue={() => quickCreateIssueMutation.reset()}
          />
        )}
      </section>

      <CreateProjectDialog
        isOpen={activeCreateDialog === 'project'}
        onSubmit={handleCreateProject}
        onClose={closeCreateDialog}
        canCreateProject={canLoadCurrentWorkspace && currentWorkspace?.role !== 'GUEST'}
        isSubmitting={createProjectMutation.isPending}
        error={createProjectMutation.error}
      />
      <CreateWorkspaceDialog
        isOpen={isCreateWorkspaceDialogOpen}
        onSubmit={(values) => createWorkspaceMutation.mutate(values)}
        onClose={closeCreateWorkspaceDialog}
        isSubmitting={createWorkspaceMutation.isPending}
        error={createWorkspaceMutation.error}
      />
      <WorkspaceInvitationsDialog
        isOpen={isWorkspaceInvitationsDialogOpen}
        currentWorkspace={currentWorkspace}
        invitations={workspaceInvitations}
        latestInvitationLink={latestInvitationLink}
        isLoading={workspaceInvitationsQuery.isLoading}
        isMutating={
          createWorkspaceInvitationMutation.isPending ||
          reissueWorkspaceInvitationMutation.isPending ||
          revokeWorkspaceInvitationMutation.isPending
        }
        error={
          workspaceInvitationsQuery.error ??
          createWorkspaceInvitationMutation.error ??
          reissueWorkspaceInvitationMutation.error ??
          revokeWorkspaceInvitationMutation.error
        }
        onSubmit={async (values) => {
          await createWorkspaceInvitationMutation.mutateAsync(values)
        }}
        onReissue={(invitationId) => reissueWorkspaceInvitationMutation.mutate(invitationId)}
        onRevoke={(invitationId) => revokeWorkspaceInvitationMutation.mutate(invitationId)}
        onClose={closeWorkspaceInvitationsDialog}
      />
      <CreateIssueDialog
        isOpen={activeCreateDialog === 'issue'}
        selectedProject={selectedProject}
        projectWorkflowStates={projectWorkflowStates}
        initialWorkflowStateId={createIssueDefaultWorkflowStateId}
        initialTitle={createIssueDefaultTitle}
        initialAssigneeUserId={createIssueDefaultAssigneeUserId}
        projectLabels={projectLabels}
        projectMembers={activeProjectMembers}
        onCreateLabel={handleCreateIssueLabel}
        onSubmit={handleCreateIssue}
        onClose={closeCreateDialog}
        isSubmitting={createIssueMutation.isPending}
        isCreatingLabel={createProjectLabelMutation.isPending}
        error={createIssueMutation.error}
        createLabelError={createProjectLabelMutation.error}
      />
      <ProjectMembersDialog
        isOpen={isProjectMembersDialogOpen}
        selectedProject={selectedProject}
        projectMembers={projectMembers}
        workspaceMembers={workspaceMembers}
        addableWorkspaceMembers={addableWorkspaceMembers}
        onSubmit={handleAddProjectMember}
        onUpdateRole={handleUpdateProjectMemberRole}
        onRemove={handleRemoveProjectMember}
        onClose={closeProjectMembersDialog}
        canManageMembers={canManageProjectMembers}
        isLoadingProjectMembers={projectMembersQuery.isLoading}
        isLoadingWorkspaceMembers={workspaceMembersQuery.isLoading}
        projectMembersError={projectMembersQuery.error}
        workspaceMembersError={workspaceMembersQuery.error}
        isSubmitting={addProjectMemberMutation.isPending}
        isMutating={
          addProjectMemberMutation.isPending ||
          updateProjectMemberMutation.isPending ||
          removeProjectMemberMutation.isPending
        }
        updatingMemberId={
          updateProjectMemberMutation.isPending
            ? (updateProjectMemberMutation.variables?.memberId ?? null)
            : null
        }
        removingMemberId={
          removeProjectMemberMutation.isPending
            ? (removeProjectMemberMutation.variables?.memberId ?? null)
            : null
        }
        addError={addProjectMemberMutation.error}
        updateError={updateProjectMemberMutation.error}
        removeError={removeProjectMemberMutation.error}
      />
      <ProjectWorkflowDialog
        isOpen={isProjectWorkflowDialogOpen}
        selectedProject={selectedProject}
        workflowStates={projectWorkflowStates}
        onSubmit={handleCreateProjectWorkflowState}
        onUpdate={handleUpdateProjectWorkflowState}
        onReorder={handleReorderProjectWorkflowState}
        onClose={closeProjectWorkflowDialog}
        canCreateWorkflowStates={canManageProjectMembers}
        isLoadingWorkflowStates={projectWorkflowStatesQuery.isLoading}
        workflowStatesError={projectWorkflowStatesQuery.error}
        isSubmitting={createProjectWorkflowStateMutation.isPending}
        isUpdating={updateProjectWorkflowStateMutation.isPending}
        isReordering={reorderProjectWorkflowStatesMutation.isPending}
        error={
          createProjectWorkflowStateMutation.error ??
          updateProjectWorkflowStateMutation.error ??
          reorderProjectWorkflowStatesMutation.error
        }
      />
    </main>
  )
}

function ProjectIssuesView({
  currentWorkspace,
  currentUser,
  selectedProject,
  projectMembers,
  projectLabels,
  projectWorkflowStates,
  projectBoard,
  issues,
  issueGroups,
  issueViewMode,
  boardIssueView,
  selectedIssueId,
  isLoadingIssues,
  issuesError,
  isLoadingProjectBoard,
  projectBoardError,
  reorderIssueError,
  isReorderingIssue,
  quickCreateIssueError,
  isQuickCreatingIssue,
  canUseQuickCreateShortcut,
  isLoadingProjectMembers,
  isLoadingProjects,
  issueSearchQuery,
  issueWorkflowFilter,
  issuePriorityFilter,
  issueLabelFilter,
  issueAssigneeFilter,
  hasIssueFilters,
  onIssueSearchQueryChange,
  onIssueWorkflowFilterChange,
  onIssuePriorityFilterChange,
  onIssueLabelFilterChange,
  onIssueAssigneeFilterChange,
  onIssueViewModeChange,
  onBoardIssueViewChange,
  onClearIssueFilters,
  onOpenProjectMembers,
  onOpenProjectWorkflow,
  onOpenCreateIssue,
  onIssueSelect,
  onReorderIssue,
  onQuickCreateIssue,
  onResetQuickCreateIssue,
}: {
  currentWorkspace: AuthWorkspace | null
  currentUser: AuthUser | null
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  projectWorkflowStates: ProjectWorkflowState[]
  projectBoard: ProjectBoard | null
  issues: IssueSummary[]
  issueGroups: IssueGroup[]
  issueViewMode: IssueViewMode
  boardIssueView: BoardIssueView
  selectedIssueId: string | null
  isLoadingIssues: boolean
  issuesError: Error | null
  isLoadingProjectBoard: boolean
  projectBoardError: Error | null
  reorderIssueError: Error | null
  isReorderingIssue: boolean
  quickCreateIssueError: Error | null
  isQuickCreatingIssue: boolean
  canUseQuickCreateShortcut: boolean
  isLoadingProjectMembers: boolean
  isLoadingProjects: boolean
  issueSearchQuery: string
  issueWorkflowFilter: IssueWorkflowFilter
  issuePriorityFilter: IssuePriority | ''
  issueLabelFilter: string
  issueAssigneeFilter: string
  hasIssueFilters: boolean
  onIssueSearchQueryChange: (query: string) => void
  onIssueWorkflowFilterChange: (status: IssueWorkflowFilter) => void
  onIssuePriorityFilterChange: (priority: IssuePriority | '') => void
  onIssueLabelFilterChange: (labelId: string) => void
  onIssueAssigneeFilterChange: (assigneeUserId: string) => void
  onIssueViewModeChange: (viewMode: IssueViewMode) => void
  onBoardIssueViewChange: (view: BoardIssueView) => void
  onClearIssueFilters: () => void
  onOpenProjectMembers: () => void
  onOpenProjectWorkflow: () => void
  onOpenCreateIssue: (
    workflowState?: ProjectWorkflowState | null,
    seed?: CreateIssueDialogSeed,
  ) => void
  onIssueSelect: (issueId: string) => void
  onReorderIssue: (reorder: KanbanReorder) => Promise<void>
  onQuickCreateIssue: (
    variables: Omit<QuickCreateIssueMutationVariables, 'projectId'>,
  ) => Promise<IssueSummary | null>
  onResetQuickCreateIssue: () => void
}) {
  const shouldShowEmptyIssues =
    issueViewMode === 'LIST' &&
    selectedProject &&
    !isLoadingIssues &&
    !issuesError &&
    issues.length === 0
  const visibleProjectBoard = useMemo(
    () => filterProjectBoard(projectBoard, boardIssueView, currentUser?.id ?? null),
    [boardIssueView, currentUser?.id, projectBoard],
  )
  const boardIssueCount =
    visibleProjectBoard?.columns.reduce(
      (count, column) => count + column.issues.length,
      0,
    ) ?? 0
  const visibleIssueCount = issueViewMode === 'BOARD' ? boardIssueCount : issues.length

  return (
    <div className="content-page">
      <header className="content-header">
        <div>
          <BreadcrumbLine
            items={[currentWorkspace?.name ?? 'Workspace', selectedProject?.name ?? 'Issues']}
          />
          <h1>{selectedProject?.name ?? 'Select a project'}</h1>
          {selectedProject?.description ? <p>{selectedProject.description}</p> : null}
        </div>
        <div className="content-header-actions">
          {selectedProject ? (
            <div className="issue-view-switch" role="group" aria-label="Issue view">
              <Button
                type="button"
                variant={issueViewMode === 'BOARD' ? 'secondary' : 'ghost'}
                size="sm"
                aria-pressed={issueViewMode === 'BOARD'}
                onClick={() => onIssueViewModeChange('BOARD')}
              >
                <FolderKanban aria-hidden="true" />
                Board
              </Button>
              <Button
                type="button"
                variant={issueViewMode === 'LIST' ? 'secondary' : 'ghost'}
                size="sm"
                aria-pressed={issueViewMode === 'LIST'}
                onClick={() => onIssueViewModeChange('LIST')}
              >
                <LayoutList aria-hidden="true" />
                List
              </Button>
            </div>
          ) : null}
          {selectedProject ? (
            <span className="app-pill">
              {issueViewMode === 'BOARD' ? (
                <FolderKanban aria-hidden="true" />
              ) : (
                <LayoutList aria-hidden="true" />
              )}
              {visibleIssueCount} issues
            </span>
          ) : null}
          {selectedProject ? (
            <Button
              type="button"
              variant="outline"
              onClick={onOpenProjectWorkflow}
              aria-label="Open workflow states"
            >
              <CircleDot aria-hidden="true" />
              {projectWorkflowStates.length} statuses
            </Button>
          ) : null}
          {selectedProject ? (
            <Button
              type="button"
              variant="outline"
              onClick={onOpenProjectMembers}
              disabled={isLoadingProjectMembers}
              aria-label="Open project members"
            >
              <Users aria-hidden="true" />
              {isLoadingProjectMembers ? 'Members' : `${projectMembers.length} members`}
            </Button>
          ) : null}
          <Button
            type="button"
            onClick={() => onOpenCreateIssue()}
            disabled={!selectedProject}
            aria-label="Create issue"
          >
            <Plus aria-hidden="true" />
            New issue
          </Button>
        </div>
      </header>

      {isLoadingProjects ? <InlineState>Loading workspace projects.</InlineState> : null}
      {!selectedProject && !isLoadingProjects ? (
        <EmptyState
          title="No project selected"
          body="Create or select a project from the sidebar to start tracking issues."
        />
      ) : null}
      {selectedProject && issueViewMode === 'BOARD' ? (
        <div className="board-view-switch" role="group" aria-label="Board issue view">
          <Button
            type="button"
            variant={boardIssueView === 'ALL' ? 'secondary' : 'ghost'}
            size="sm"
            aria-pressed={boardIssueView === 'ALL'}
            onClick={() => onBoardIssueViewChange('ALL')}
          >
            <FolderKanban aria-hidden="true" />
            All
          </Button>
          <Button
            type="button"
            variant={boardIssueView === 'MINE' ? 'secondary' : 'ghost'}
            size="sm"
            aria-pressed={boardIssueView === 'MINE'}
            onClick={() => onBoardIssueViewChange('MINE')}
          >
            <UserCircle aria-hidden="true" />
            My issues
          </Button>
          <Button
            type="button"
            variant={boardIssueView === 'UNASSIGNED' ? 'secondary' : 'ghost'}
            size="sm"
            aria-pressed={boardIssueView === 'UNASSIGNED'}
            onClick={() => onBoardIssueViewChange('UNASSIGNED')}
          >
            <Circle aria-hidden="true" />
            Unassigned
          </Button>
        </div>
      ) : null}
      {issueViewMode === 'LIST' && isLoadingIssues ? (
        <InlineState>Loading issues.</InlineState>
      ) : null}
      {issueViewMode === 'LIST' && issuesError ? <ErrorState error={issuesError} /> : null}
      {issueViewMode === 'BOARD' && isLoadingProjectBoard && !projectBoard ? (
        <BoardLoadingState workflowStates={projectWorkflowStates} />
      ) : null}
      {issueViewMode === 'BOARD' && projectBoardError ? (
        <ErrorState error={projectBoardError} />
      ) : null}

      {selectedProject && issueViewMode === 'LIST' ? (
        <div className="issue-filter-bar" aria-label="Issue filters">
          <label className="issue-search-field">
            <Search aria-hidden="true" />
            <input
              type="search"
              placeholder="Search issues"
              value={issueSearchQuery}
              onChange={(event) => onIssueSearchQueryChange(event.target.value)}
            />
          </label>
          <label className="app-field">
            Status
            <select
              value={issueWorkflowFilter}
              onChange={(event) => onIssueWorkflowFilterChange(event.target.value as IssueWorkflowFilter)}
            >
              <option value="ACTIVE">Active</option>
              {projectWorkflowStates.map((workflowState) => (
                <option key={workflowState.id} value={workflowState.id}>
                  {workflowState.name}
                </option>
              ))}
              <option value="ARCHIVED">Archived</option>
            </select>
          </label>
          <label className="app-field">
            Priority
            <select
              value={issuePriorityFilter}
              onChange={(event) => onIssuePriorityFilterChange(event.target.value as IssuePriority | '')}
            >
              <option value="">Any priority</option>
              {ISSUE_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {PRIORITY_LABELS[priority]}
                </option>
              ))}
            </select>
          </label>
          <label className="app-field">
            Label
            <select
              value={issueLabelFilter}
              onChange={(event) => onIssueLabelFilterChange(event.target.value)}
              disabled={projectLabels.length === 0}
            >
              <option value="">Any label</option>
              {projectLabels.map((label) => (
                <option key={label.id} value={label.id}>
                  {label.name}
                </option>
              ))}
            </select>
          </label>
          <label className="app-field">
            Assignee
            <select
              value={issueAssigneeFilter}
              onChange={(event) => onIssueAssigneeFilterChange(event.target.value)}
              disabled={projectMembers.length === 0}
            >
              <option value="">Any assignee</option>
              {projectMembers.map((member) => (
                <option key={member.id} value={member.userId}>
                  {member.displayName || member.email}
                </option>
              ))}
            </select>
          </label>
          <Button
            type="button"
            variant="ghost"
            onClick={onClearIssueFilters}
            disabled={!hasIssueFilters}
          >
            Clear
          </Button>
        </div>
      ) : null}

      {shouldShowEmptyIssues ? (
        <EmptyState
          title={hasIssueFilters ? 'No matching issues' : 'No active issues yet'}
          body={
            hasIssueFilters
              ? 'Adjust the search or filters to find issues in this project.'
              : 'Create an issue to start tracking work in this project.'
          }
        />
      ) : null}

      {selectedProject &&
      issueViewMode === 'BOARD' &&
      visibleProjectBoard ? (
        <KanbanBoard
          board={visibleProjectBoard}
          boardIssueView={boardIssueView}
          currentUserId={currentUser?.id ?? null}
          selectedIssueId={selectedIssueId}
          isReordering={isReorderingIssue}
          isQuickCreating={isQuickCreatingIssue}
          canUseQuickCreateShortcut={canUseQuickCreateShortcut}
          reorderError={reorderIssueError}
          quickCreateError={quickCreateIssueError}
          key={`${visibleProjectBoard.projectId}-${boardIssueView}`}
          onIssueSelect={onIssueSelect}
          onOpenFullCreate={onOpenCreateIssue}
          onReorder={onReorderIssue}
          onQuickCreate={onQuickCreateIssue}
          onResetQuickCreate={onResetQuickCreateIssue}
        />
      ) : null}

      {selectedProject && issueViewMode === 'LIST' && !issuesError && issues.length > 0 ? (
        <div className="status-list" aria-label="Issues grouped by status">
          {issueGroups.map((group) => (
            <IssueStatusSection
              group={group}
              key={group.workflowState?.id ?? group.status}
              selectedIssueId={selectedIssueId}
              onIssueSelect={onIssueSelect}
              onOpenCreateIssue={onOpenCreateIssue}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

function BoardLoadingState({
  workflowStates,
}: {
  workflowStates: ProjectWorkflowState[]
}) {
  const loadingColumns =
    workflowStates.length > 0 ? workflowStates : Array<ProjectWorkflowState | null>(3).fill(null)

  return (
    <section className="kanban-board-region" aria-label="Loading project board" role="status">
      <p className="kanban-save-state">
        <Loader2 className="auth-spin" aria-hidden="true" />
        Loading board
      </p>
      <div className="kanban-board-scroll">
        <div className="kanban-board" aria-hidden="true">
          {loadingColumns.map((workflowState, index) => (
            <section className="kanban-column kanban-column-loading" key={workflowState?.id ?? index}>
              <header className="kanban-column-header">
                <span className="kanban-column-title">
                  <Circle aria-hidden="true" className="status-icon" />
                  <strong>{workflowState?.name ?? 'Status'}</strong>
                </span>
              </header>
              <div className="kanban-loading-block" />
            </section>
          ))}
        </div>
      </div>
    </section>
  )
}

function KanbanBoard({
  board,
  boardIssueView,
  currentUserId,
  selectedIssueId,
  isReordering,
  isQuickCreating,
  canUseQuickCreateShortcut,
  reorderError,
  quickCreateError,
  onIssueSelect,
  onOpenFullCreate,
  onReorder,
  onQuickCreate,
  onResetQuickCreate,
}: {
  board: ProjectBoard
  boardIssueView: BoardIssueView
  currentUserId: string | null
  selectedIssueId: string | null
  isReordering: boolean
  isQuickCreating: boolean
  canUseQuickCreateShortcut: boolean
  reorderError: Error | null
  quickCreateError: Error | null
  onIssueSelect: (issueId: string) => void
  onOpenFullCreate: (
    workflowState?: ProjectWorkflowState | null,
    seed?: CreateIssueDialogSeed,
  ) => void
  onReorder: (reorder: KanbanReorder) => Promise<void>
  onQuickCreate: (
    variables: Omit<QuickCreateIssueMutationVariables, 'projectId'>,
  ) => Promise<IssueSummary | null>
  onResetQuickCreate: () => void
}) {
  const [activeIssueId, setActiveIssueId] = useState<string | null>(null)
  const [composerWorkflowStateId, setComposerWorkflowStateId] = useState<string | null>(null)
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: { distance: 6 },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  )
  const activeIssue = activeIssueId ? findBoardIssue(board, activeIssueId) : null
  const isSortingEnabled = boardIssueView === 'ALL'
  const defaultAssigneeUserId =
    boardIssueView === 'MINE' ? (currentUserId ?? undefined) : undefined
  const emptyColumnLabel = boardEmptyColumnLabel(boardIssueView)

  useEffect(() => {
    if (!canUseQuickCreateShortcut || isReordering || isQuickCreating) {
      return
    }

    function handleQuickCreateShortcut(event: KeyboardEvent) {
      const target = event.target
      const isEditing =
        target instanceof HTMLElement &&
        (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
      if (
        event.isComposing ||
        isEditing ||
        event.metaKey ||
        event.ctrlKey ||
        event.altKey ||
        event.key.toLowerCase() !== 'n'
      ) {
        return
      }

      const defaultColumn =
        board.columns.find((column) => column.workflowState.category === 'TODO') ??
        board.columns[0]
      if (!defaultColumn) {
        return
      }

      event.preventDefault()
      onResetQuickCreate()
      setComposerWorkflowStateId(defaultColumn.workflowState.id)
    }

    document.addEventListener('keydown', handleQuickCreateShortcut)
    return () => document.removeEventListener('keydown', handleQuickCreateShortcut)
  }, [
    board.columns,
    canUseQuickCreateShortcut,
    isQuickCreating,
    isReordering,
    onResetQuickCreate,
  ])

  function openQuickCreate(workflowStateId: string) {
    if (isReordering || isQuickCreating) {
      return
    }

    onResetQuickCreate()
    setComposerWorkflowStateId(workflowStateId)
  }

  function closeQuickCreate() {
    if (!isQuickCreating) {
      setComposerWorkflowStateId(null)
    }
  }

  function expandQuickCreate(workflowState: ProjectWorkflowState, title: string) {
    if (isQuickCreating) {
      return
    }

    setComposerWorkflowStateId(null)
    onResetQuickCreate()
    onOpenFullCreate(workflowState, {
      title,
      assigneeUserId: defaultAssigneeUserId,
    })
  }

  function handleDragStart(event: DragStartEvent) {
    setActiveIssueId(String(event.active.id))
  }

  async function handleDragEnd(event: DragEndEvent) {
    setActiveIssueId(null)
    if (!event.over || isReordering || !isSortingEnabled) {
      return
    }

    const overData = event.over.data.current as KanbanDragData | undefined
    if (!overData) {
      return
    }

    const issueId = String(event.active.id)
    const overIssueId = overData.type === 'issue' ? String(event.over.id) : null
    const optimisticBoard = buildOptimisticBoard(
      board,
      issueId,
      overData.workflowStateId,
      overIssueId,
    )
    if (!optimisticBoard) {
      return
    }

    const targetColumn = optimisticBoard.columns.find(
      (column) => column.workflowState.id === overData.workflowStateId,
    )
    if (!targetColumn) {
      return
    }

    try {
      await onReorder({
        issueId,
        workflowStateId: overData.workflowStateId,
        orderedIssueIds: targetColumn.issues.map((issue) => issue.id),
        optimisticBoard,
      })
    } catch {
      // The mutation restores the previous board and renders the request error.
    }
  }

  return (
    <section
      className="kanban-board-region"
      aria-label="Project board"
      aria-busy={isReordering || isQuickCreating}
    >
      {reorderError ? <ErrorState error={reorderError} /> : null}
      {isReordering ? (
        <p className="kanban-save-state" role="status">
          <Loader2 className="auth-spin" aria-hidden="true" />
          Saving board
        </p>
      ) : null}
      <DndContext
        sensors={sensors}
        collisionDetection={closestCorners}
        onDragStart={handleDragStart}
        onDragCancel={() => setActiveIssueId(null)}
        onDragEnd={handleDragEnd}
      >
        <div className="kanban-board-scroll">
          <div className="kanban-board">
            {board.columns.map((column) => (
              <KanbanColumn
                column={column}
                defaultAssigneeUserId={defaultAssigneeUserId}
                emptyLabel={emptyColumnLabel}
                isReordering={isReordering}
                isSortingEnabled={isSortingEnabled}
                isComposerOpen={composerWorkflowStateId === column.workflowState.id}
                isQuickCreating={isQuickCreating}
                quickCreateError={
                  composerWorkflowStateId === column.workflowState.id
                    ? quickCreateError
                    : null
                }
                selectedIssueId={selectedIssueId}
                key={column.workflowState.id}
                onIssueSelect={onIssueSelect}
                onOpenQuickCreate={openQuickCreate}
                onCloseQuickCreate={closeQuickCreate}
                onExpandQuickCreate={expandQuickCreate}
                onQuickCreate={onQuickCreate}
              />
            ))}
          </div>
        </div>
        <DragOverlay>
          {activeIssue ? (
            <article className="kanban-card kanban-card-overlay">
              <KanbanIssueCardContent issue={activeIssue} />
            </article>
          ) : null}
        </DragOverlay>
      </DndContext>
    </section>
  )
}

function KanbanColumn({
  column,
  defaultAssigneeUserId,
  emptyLabel,
  selectedIssueId,
  isReordering,
  isSortingEnabled,
  isComposerOpen,
  isQuickCreating,
  quickCreateError,
  onIssueSelect,
  onOpenQuickCreate,
  onCloseQuickCreate,
  onExpandQuickCreate,
  onQuickCreate,
}: {
  column: BoardColumn
  defaultAssigneeUserId?: string
  emptyLabel: string
  selectedIssueId: string | null
  isReordering: boolean
  isSortingEnabled: boolean
  isComposerOpen: boolean
  isQuickCreating: boolean
  quickCreateError: Error | null
  onIssueSelect: (issueId: string) => void
  onOpenQuickCreate: (workflowStateId: string) => void
  onCloseQuickCreate: () => void
  onExpandQuickCreate: (workflowState: ProjectWorkflowState, title: string) => void
  onQuickCreate: (
    variables: Omit<QuickCreateIssueMutationVariables, 'projectId'>,
  ) => Promise<IssueSummary | null>
}) {
  const { isOver, setNodeRef } = useDroppable({
    id: kanbanColumnId(column.workflowState.id),
    data: {
      type: 'column',
      workflowStateId: column.workflowState.id,
    } satisfies KanbanDragData,
    disabled: !isSortingEnabled || isReordering,
  })

  return (
    <section className="kanban-column" aria-label={`${column.workflowState.name} issues`}>
      <header className="kanban-column-header">
        <span className="kanban-column-title">
          <StatusIcon status={column.workflowState.category} />
          <strong>{column.workflowState.name}</strong>
          <small>{column.issues.length}</small>
        </span>
        <Button
          type="button"
          variant="ghost"
          size="icon-xs"
          disabled={isReordering || isQuickCreating}
          onClick={() => onOpenQuickCreate(column.workflowState.id)}
          aria-label={`Quick create issue in ${column.workflowState.name}`}
          title="Quick create issue"
        >
          <Plus aria-hidden="true" />
        </Button>
      </header>
      <div className="kanban-column-body" data-over={isOver} ref={setNodeRef}>
        <SortableContext
          items={column.issues.map((issue) => issue.id)}
          strategy={verticalListSortingStrategy}
        >
          {column.issues.map((issue) => (
            <SortableIssueCard
              issue={issue}
              workflowStateId={column.workflowState.id}
              isActive={issue.id === selectedIssueId}
              isReordering={isReordering}
              isSortingEnabled={isSortingEnabled}
              key={issue.id}
              onIssueSelect={onIssueSelect}
            />
          ))}
        </SortableContext>
        {isComposerOpen ? (
          <InlineIssueComposer
            workflowState={column.workflowState}
            defaultAssigneeUserId={defaultAssigneeUserId}
            isSubmitting={isQuickCreating}
            error={quickCreateError}
            onSubmit={onQuickCreate}
            onClose={onCloseQuickCreate}
            onExpand={onExpandQuickCreate}
          />
        ) : null}
        {column.issues.length === 0 && !isComposerOpen ? (
          <p className="kanban-column-empty">{emptyLabel}</p>
        ) : null}
      </div>
    </section>
  )
}

function InlineIssueComposer({
  workflowState,
  defaultAssigneeUserId,
  isSubmitting,
  error,
  onSubmit,
  onClose,
  onExpand,
}: {
  workflowState: ProjectWorkflowState
  defaultAssigneeUserId?: string
  isSubmitting: boolean
  error: Error | null
  onSubmit: (
    variables: Omit<QuickCreateIssueMutationVariables, 'projectId'>,
  ) => Promise<IssueSummary | null>
  onClose: () => void
  onExpand: (workflowState: ProjectWorkflowState, title: string) => void
}) {
  const {
    register,
    handleSubmit,
    reset,
    setFocus,
    control,
    formState: { errors },
  } = useForm<QuickCreateIssueFormValues>({
    resolver: zodResolver(quickCreateIssueFormSchema),
    defaultValues: { title: '' },
  })
  const title = useWatch({ control, name: 'title' }) ?? ''

  async function submitQuickIssue(values: QuickCreateIssueFormValues) {
    try {
      const issue = await onSubmit({
        title: values.title.trim(),
        workflowStateId: workflowState.id,
        assigneeUserId: defaultAssigneeUserId,
      })
      if (!issue) {
        return
      }

      reset({ title: '' })
      setFocus('title')
    } catch {
      // The request error is rendered below and the draft title is retained.
    }
  }

  return (
    <form
      className="kanban-inline-composer"
      onSubmit={handleSubmit(submitQuickIssue)}
      aria-busy={isSubmitting}
      noValidate
    >
      <input
        autoFocus
        aria-label={`Issue title for ${workflowState.name}`}
        placeholder="Issue title"
        disabled={isSubmitting}
        {...register('title')}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && event.nativeEvent.isComposing) {
            event.preventDefault()
          }
          if (event.key === 'Escape') {
            event.preventDefault()
            event.stopPropagation()
            onClose()
          }
        }}
      />
      {errors.title?.message ? (
        <InlineNotice tone="warning">{errors.title.message}</InlineNotice>
      ) : null}
      {error ? <InlineNotice tone="warning">{getErrorMessage(error)}</InlineNotice> : null}
      <div className="kanban-inline-composer-actions">
        <Button
          type="button"
          variant="ghost"
          size="icon-xs"
          disabled={isSubmitting}
          onClick={() => onExpand(workflowState, title.trim())}
          aria-label="Open full issue form"
          title="Open full issue form"
        >
          <Maximize2 aria-hidden="true" />
        </Button>
        <span>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            disabled={isSubmitting}
            onClick={onClose}
            aria-label="Close quick create"
            title="Close"
          >
            <X aria-hidden="true" />
          </Button>
          <Button
            type="submit"
            size="icon-xs"
            disabled={!title.trim() || isSubmitting}
            aria-label="Create issue"
            title="Create issue"
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Plus aria-hidden="true" />
            )}
          </Button>
        </span>
      </div>
    </form>
  )
}

function SortableIssueCard({
  issue,
  workflowStateId,
  isActive,
  isReordering,
  isSortingEnabled,
  onIssueSelect,
}: {
  issue: IssueSummary
  workflowStateId: string
  isActive: boolean
  isReordering: boolean
  isSortingEnabled: boolean
  onIssueSelect: (issueId: string) => void
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: issue.id,
    data: {
      type: 'issue',
      workflowStateId,
    } satisfies KanbanDragData,
    disabled: isReordering || !isSortingEnabled,
  })

  return (
    <article
      className="kanban-card"
      data-active={isActive}
      data-dragging={isDragging}
      data-sortable={isSortingEnabled}
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
      }}
    >
      {isSortingEnabled ? (
        <button
          className="kanban-drag-handle"
          type="button"
          disabled={isReordering}
          aria-label={`Move ${issue.title}`}
          title="Move issue"
          {...attributes}
          {...listeners}
        >
          <GripVertical aria-hidden="true" />
        </button>
      ) : null}
      <button
        className="kanban-card-open"
        type="button"
        onClick={() => onIssueSelect(issue.id)}
      >
        <KanbanIssueCardContent issue={issue} />
      </button>
    </article>
  )
}

function KanbanIssueCardContent({ issue }: { issue: IssueSummary }) {
  return (
    <span className="kanban-card-content">
      <strong>{issue.title}</strong>
      {issue.labels.length > 0 ? (
        <span className="issue-label-row">
          {issue.labels.map((label) => (
            <LabelBadge label={label} key={label.id} />
          ))}
        </span>
      ) : null}
      <span className="kanban-card-meta">
        <PriorityBadge priority={issue.priority} />
        <span title={issue.assignee?.email ?? 'Unassigned'}>
          <UserCircle aria-hidden="true" />
          {issue.assignee ? issue.assignee.displayName || issue.assignee.email : 'Unassigned'}
        </span>
        {issue.dueDate ? (
          <span>
            <CalendarDays aria-hidden="true" />
            {formatDateOnly(issue.dueDate)}
          </span>
        ) : null}
        <span>
          <MessageSquare aria-hidden="true" />
          {issue.commentCount ?? 0}
        </span>
      </span>
    </span>
  )
}

function IssueStatusSection({
  group,
  selectedIssueId,
  onIssueSelect,
  onOpenCreateIssue,
}: {
  group: IssueGroup
  selectedIssueId: string | null
  onIssueSelect: (issueId: string) => void
  onOpenCreateIssue: (workflowState?: ProjectWorkflowState | null) => void
}) {
  const canCreateIssueInStatus = group.status !== 'ARCHIVED'

  return (
    <section className="status-section">
      <div className="status-section-header">
        <span>
          <StatusIcon status={group.status} />
          {group.label}
        </span>
        <span className="status-section-actions">
          <small>{group.issues.length}</small>
          {canCreateIssueInStatus ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={() => onOpenCreateIssue(group.workflowState)}
              aria-label={`Create issue in ${group.label}`}
              title="Create issue"
            >
              <Plus aria-hidden="true" />
            </Button>
          ) : null}
        </span>
      </div>
      {group.issues.length === 0 ? (
        <p className="status-empty">No issues in this status.</p>
      ) : (
        <div className="issue-list">
          {group.issues.map((issue) => (
            <IssueRow
              issue={issue}
              isActive={issue.id === selectedIssueId}
              key={issue.id}
              onSelectIssue={onIssueSelect}
            />
          ))}
        </div>
      )}
    </section>
  )
}

function IssueRow({
  issue,
  isActive,
  onSelectIssue,
}: {
  issue: IssueSummary
  isActive: boolean
  onSelectIssue: (issueId: string) => void
}) {
  return (
    <button
      className="issue-row"
      data-active={isActive}
      type="button"
      onClick={() => onSelectIssue(issue.id)}
    >
      <span className="issue-row-status">
        <StatusIcon status={statusForIcon(issue)} />
      </span>
      <span className="issue-row-main">
        <strong>{issue.title}</strong>
        {issue.description ? <small>{issue.description}</small> : null}
        {issue.labels.length > 0 ? (
          <span className="issue-label-row">
            {issue.labels.map((label) => (
              <LabelBadge label={label} key={label.id} />
            ))}
          </span>
        ) : null}
      </span>
      <span className="issue-row-meta">
        <span className="issue-comment-count">
          <CircleDot aria-hidden="true" />
          {issue.status === 'ARCHIVED' ? 'Archived' : issue.workflowState.name}
        </span>
        <PriorityBadge priority={issue.priority} />
        <span className="issue-comment-count">
          <UserCircle aria-hidden="true" />
          {issue.assignee ? issue.assignee.displayName || issue.assignee.email : 'Unassigned'}
        </span>
        {issue.dueDate ? (
          <span className="issue-comment-count">
            <CalendarDays aria-hidden="true" />
            {formatDateOnly(issue.dueDate)}
          </span>
        ) : null}
        <span className="issue-comment-count">
          <MessageSquare aria-hidden="true" />
          {issue.commentCount ?? 0}
        </span>
        <span>{formatDate(issue.updatedAt)}</span>
      </span>
    </button>
  )
}

function IssueDetailView({
  issue,
  issueDetail,
  selectedProject,
  projectMembers,
  projectLabels,
  projectWorkflowStates,
  currentWorkspace,
  currentUser,
  activities,
  isLoadingIssue,
  issueError,
  isLoadingActivities,
  activitiesError,
  onSubmitComment,
  isSubmittingComment,
  commentError,
  onBackToProject,
  onUpdateIssue,
  onArchiveIssue,
  isUpdatingIssue,
  updateIssueError,
  onResetUpdateIssueError,
}: {
  issue: IssueSummary | IssueDetail | null
  issueDetail: IssueDetail | undefined
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  projectWorkflowStates: ProjectWorkflowState[]
  currentWorkspace: AuthWorkspace | null
  currentUser: AuthUser | null
  activities: ActivityEvent[]
  isLoadingIssue: boolean
  issueError: Error | null
  isLoadingActivities: boolean
  activitiesError: Error | null
  onSubmitComment: (values: CommentFormValues) => Promise<void>
  isSubmittingComment: boolean
  commentError: Error | null
  onBackToProject: () => void
  onUpdateIssue: (request: UpdateIssueRequest) => Promise<void>
  onArchiveIssue: () => Promise<void>
  isUpdatingIssue: boolean
  updateIssueError: Error | null
  onResetUpdateIssueError: () => void
}) {
  const comments = issueDetail?.comments ?? []
  const [editingIssueId, setEditingIssueId] = useState<string | null>(null)
  const {
    register: registerIssueContent,
    handleSubmit: handleIssueContentSubmit,
    reset: resetIssueContentForm,
    control: issueContentControl,
    formState: { errors: issueContentErrors },
  } = useForm<IssueContentFormValues>({
    resolver: zodResolver(issueContentFormSchema),
    defaultValues: {
      title: issue?.title ?? '',
      description: issue?.description ?? '',
    },
  })
  const issueId = issue?.id ?? null
  const isEditingIssueContent = Boolean(issueId && editingIssueId === issueId)
  const draftIssueTitle =
    useWatch({ control: issueContentControl, name: 'title' }) ?? ''
  const draftIssueDescription =
    useWatch({ control: issueContentControl, name: 'description' }) ?? ''

  useEffect(() => {
    if (!isEditingIssueContent) {
      resetIssueContentForm({
        title: issue?.title ?? '',
        description: issue?.description ?? '',
      })
    }
  }, [
    isEditingIssueContent,
    issue?.description,
    issue?.id,
    issue?.title,
    resetIssueContentForm,
  ])

  async function handleSaveIssueContent(values: IssueContentFormValues) {
    try {
      await onUpdateIssue({
        title: values.title.trim(),
        description: values.description.trim() || null,
      })
      setEditingIssueId(null)
    } catch {
      // The shared mutation error is rendered below so the user keeps their edits.
    }
  }

  function startIssueContentEdit() {
    if (!issue) {
      return
    }

    onResetUpdateIssueError()
    resetIssueContentForm({
      title: issue.title,
      description: issue.description ?? '',
    })
    setEditingIssueId(issue.id)
  }

  function cancelIssueContentEdit() {
    resetIssueContentForm({
      title: issue?.title ?? '',
      description: issue?.description ?? '',
    })
    setEditingIssueId(null)
  }

  const canSaveIssueContent = Boolean(
    issue &&
      draftIssueTitle.trim() &&
      !isUpdatingIssue &&
      (draftIssueTitle.trim() !== issue.title ||
        (draftIssueDescription.trim() || null) !== (issue.description ?? null)),
  )

  if (!issue && isLoadingIssue) {
    return (
      <div className="content-page">
        <InlineState>Loading issue detail.</InlineState>
      </div>
    )
  }

  if (!issue) {
    return (
      <div className="content-page">
        {issueError ? (
          <ErrorState error={issueError} />
        ) : (
          <EmptyState title="Issue not found" body="Select an issue from the project list." />
        )}
      </div>
    )
  }

  return (
    <div className="content-page">
      <header className="content-header detail-content-header">
        <div>
          <BreadcrumbLine
            items={[
              currentWorkspace?.name ?? 'Workspace',
              selectedProject?.name ?? 'Project',
              issue.title,
            ]}
          />
          <Button type="button" variant="ghost" size="sm" onClick={onBackToProject}>
            <ArrowLeft aria-hidden="true" />
            Back to issues
          </Button>
        </div>
        <span className="app-pill">
          <PanelRight aria-hidden="true" />
          Properties
        </span>
      </header>

      <div className="issue-detail-layout">
        <article className="issue-detail-main">
          <div className="issue-title-block">
            {isEditingIssueContent ? (
              <form
                className="issue-edit-form"
                onSubmit={handleIssueContentSubmit(handleSaveIssueContent)}
                noValidate
              >
                <input
                  autoFocus
                  className="issue-title-input"
                  disabled={isUpdatingIssue}
                  aria-label="Issue title"
                  {...registerIssueContent('title')}
                />
                <textarea
                  className="issue-description-input"
                  disabled={isUpdatingIssue}
                  aria-label="Issue description"
                  placeholder="Add description..."
                  rows={5}
                  {...registerIssueContent('description')}
                />
                {issueContentErrors.title?.message ? (
                  <InlineNotice tone="warning">{issueContentErrors.title.message}</InlineNotice>
                ) : null}
                {issueContentErrors.description?.message ? (
                  <InlineNotice tone="warning">
                    {issueContentErrors.description.message}
                  </InlineNotice>
                ) : null}
                {updateIssueError ? <ErrorState error={updateIssueError} /> : null}
                <div className="issue-edit-actions">
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={cancelIssueContentEdit}
                    disabled={isUpdatingIssue}
                  >
                    Cancel
                  </Button>
                  <Button type="submit" disabled={!canSaveIssueContent}>
                    {isUpdatingIssue ? (
                      <Loader2 aria-hidden="true" className="auth-spin" />
                    ) : (
                      <Check aria-hidden="true" />
                    )}
                    Save
                  </Button>
                </div>
              </form>
            ) : (
              <div className="issue-title-display">
                <div className="issue-title-content">
                  <h1>{issue.title}</h1>
                  {issue.description ? (
                    <p>{issue.description}</p>
                  ) : (
                    <p className="muted-placeholder">No description yet.</p>
                  )}
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={startIssueContentEdit}
                >
                  <Pencil aria-hidden="true" />
                  Edit
                </Button>
              </div>
            )}
          </div>

          {isLoadingIssue ? <InlineState>Refreshing issue details.</InlineState> : null}
          {issueError ? <ErrorState error={issueError} /> : null}

          <section className="detail-section">
            <div className="detail-section-title">
              <MessageSquare aria-hidden="true" />
              <h2>Comments</h2>
            </div>
            {comments.length === 0 && !isLoadingIssue ? (
              <InlineState>No comments yet.</InlineState>
            ) : null}
            <div className="comment-list">
              {comments.map((comment) => (
                <article className="comment-item" key={comment.id}>
                  <div className="comment-meta">
                    <strong>{comment.author.displayName || comment.author.email}</strong>
                    <span>{formatDate(comment.createdAt)}</span>
                  </div>
                  <p>{comment.body}</p>
                </article>
              ))}
            </div>
            <CommentForm
              onSubmit={onSubmitComment}
              isSubmitting={isSubmittingComment}
              error={commentError}
            />
          </section>

          <section className="detail-section">
            <div className="detail-section-title">
              <Activity aria-hidden="true" />
              <h2>Activity</h2>
            </div>
            {isLoadingActivities ? <InlineState>Loading activity.</InlineState> : null}
            {activitiesError ? <ErrorState error={activitiesError} /> : null}
            {activities.length === 0 && !isLoadingActivities && !activitiesError ? (
              <InlineState>No activity recorded yet.</InlineState>
            ) : null}
            <div className="activity-list">
              {activities.map((activity) => (
                <article className="activity-item" key={activity.id}>
                  <span className="activity-dot" />
                  <div>
                    <p>{formatActivity(activity)}</p>
                    <small>{formatDate(activity.createdAt)}</small>
                  </div>
                </article>
              ))}
            </div>
          </section>
        </article>

        <IssuePropertiesPanel
          issue={issue}
          selectedProject={selectedProject}
          projectMembers={projectMembers}
          projectLabels={projectLabels}
          projectWorkflowStates={projectWorkflowStates}
          currentUser={currentUser}
          onUpdateIssue={onUpdateIssue}
          onArchiveIssue={onArchiveIssue}
          isUpdatingIssue={isUpdatingIssue}
          updateIssueError={updateIssueError}
        />
      </div>
    </div>
  )
}

function CommentForm({
  onSubmit,
  isSubmitting,
  error,
}: {
  onSubmit: (values: CommentFormValues) => Promise<void>
  isSubmitting: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<CommentFormValues>({
    resolver: zodResolver(commentFormSchema),
    defaultValues: {
      body: '',
    },
  })
  const body = useWatch({ control, name: 'body' }) ?? ''

  async function submitComment(values: CommentFormValues) {
    try {
      await onSubmit(values)
      reset({ body: '' })
    } catch {
      // The mutation error is rendered below so the draft comment stays in place.
    }
  }

  return (
    <form className="comment-form" onSubmit={handleSubmit(submitComment)} noValidate>
      <label className="app-field">
        Add comment
        <textarea
          placeholder="Leave a comment..."
          rows={4}
          disabled={isSubmitting}
          {...register('body')}
        />
      </label>
      {errors.body?.message ? <InlineNotice tone="warning">{errors.body.message}</InlineNotice> : null}
      {error ? <ErrorState error={error} /> : null}
      <Button type="submit" disabled={!body.trim() || isSubmitting}>
        {isSubmitting ? (
          <Loader2 aria-hidden="true" className="auth-spin" />
        ) : (
          <Send aria-hidden="true" />
        )}
        Comment
      </Button>
    </form>
  )
}

function IssuePropertiesPanel({
  issue,
  selectedProject,
  projectMembers,
  projectLabels,
  projectWorkflowStates,
  currentUser,
  onUpdateIssue,
  onArchiveIssue,
  isUpdatingIssue,
  updateIssueError,
}: {
  issue: IssueSummary | IssueDetail
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectLabels: ProjectLabel[]
  projectWorkflowStates: ProjectWorkflowState[]
  currentUser: AuthUser | null
  onUpdateIssue: (request: UpdateIssueRequest) => Promise<void>
  onArchiveIssue: () => Promise<void>
  isUpdatingIssue: boolean
  updateIssueError: Error | null
}) {
  const actor = issue.creator ?? issue.reporter ?? null
  const isArchived = issue.status === 'ARCHIVED'

  return (
    <aside className="issue-properties">
      <section className="property-section">
        <div className="property-section-title">
          <PanelRight aria-hidden="true" />
          <h2>Properties</h2>
        </div>
        <label className="property-field">
          <span>Status</span>
          <select
            value={issue.workflowState.id}
            onChange={(event) => {
              void onUpdateIssue({ workflowStateId: event.target.value }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue || projectWorkflowStates.length === 0}
          >
            {projectWorkflowStates.map((workflowState) => (
              <option key={workflowState.id} value={workflowState.id}>
                {workflowState.name}
              </option>
            ))}
          </select>
        </label>
        <label className="property-field">
          <span>Priority</span>
          <select
            value={issue.priority ?? ''}
            onChange={(event) => {
              void onUpdateIssue({
                priority: event.target.value ? (event.target.value as IssuePriority) : null,
              }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          >
            <option value="">No priority</option>
            {ISSUE_PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {PRIORITY_LABELS[priority]}
              </option>
            ))}
          </select>
        </label>
        <label className="property-field">
          <span>Assignee</span>
          <select
            value={issue.assignee?.id ?? ''}
            onChange={(event) => {
              void onUpdateIssue({
                assigneeUserId: event.target.value || null,
              }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          >
            <option value="">Unassigned</option>
            {projectMembers.map((member) => (
              <option key={member.id} value={member.userId}>
                {member.displayName || member.email}
              </option>
            ))}
          </select>
        </label>
        <label className="property-field">
          <span>Due date</span>
          <input
            type="date"
            value={issue.dueDate ?? ''}
            onChange={(event) => {
              void onUpdateIssue({
                dueDate: event.target.value || null,
              }).catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          />
        </label>
        <div className="property-field">
          <span>Labels</span>
          {projectLabels.length === 0 ? (
            <InlineNotice>No labels in this project.</InlineNotice>
          ) : (
            <div className="label-toggle-list">
              {projectLabels.map((label) => {
                const isSelected = issue.labels.some((issueLabel) => issueLabel.id === label.id)
                return (
                  <label className="label-toggle" key={label.id}>
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => {
                        const labelIds = isSelected
                          ? issue.labels
                              .filter((issueLabel) => issueLabel.id !== label.id)
                              .map((issueLabel) => issueLabel.id)
                          : [...issue.labels.map((issueLabel) => issueLabel.id), label.id]
                        void onUpdateIssue({ labelIds }).catch(() => undefined)
                      }}
                      disabled={isUpdatingIssue}
                    />
                    <LabelBadge label={label} />
                  </label>
                )
              })}
            </div>
          )}
        </div>
        {updateIssueError ? (
          <InlineNotice tone="warning">
            {getErrorMessage(updateIssueError)}
          </InlineNotice>
        ) : null}
        {isArchived ? (
          <InlineNotice>This issue is archived.</InlineNotice>
        ) : (
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              void onArchiveIssue().catch(() => undefined)
            }}
            disabled={isUpdatingIssue}
          >
            {isUpdatingIssue ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Archive aria-hidden="true" />
            )}
            Archive issue
          </Button>
        )}
      </section>

      <section className="property-section">
        <div className="property-row">
          <span>
            <FolderKanban aria-hidden="true" />
            Project
          </span>
          <strong>{selectedProject?.name ?? 'Unknown'}</strong>
        </div>
        <div className="property-row">
          <span>
            <UserCircle aria-hidden="true" />
            Creator
          </span>
          <strong>
            {actor
              ? actor.displayName || actor.email
              : currentUser
                ? currentUser.displayName || currentUser.email
                : 'Unknown'}
          </strong>
        </div>
        <div className="property-row">
          <span>
            <Clock3 aria-hidden="true" />
            Created
          </span>
          <strong>{formatDate(issue.createdAt)}</strong>
        </div>
        <div className="property-row">
          <span>
            <Clock3 aria-hidden="true" />
            Updated
          </span>
          <strong>{formatDate(issue.updatedAt)}</strong>
        </div>
      </section>
    </aside>
  )
}

function WorkspaceMismatchState({
  currentWorkspace,
  routeWorkspaceId,
}: {
  currentWorkspace: AuthWorkspace | null
  routeWorkspaceId: string | undefined
}) {
  return (
    <div className="content-page">
      <EmptyState
        title="Workspace is not active"
        body={`This URL points to workspace ${routeWorkspaceId ?? 'unknown'}, but your current session is ${currentWorkspace?.name ?? 'another workspace'}. Return to /app to use the current workspace.`}
      />
    </div>
  )
}

function CreateWorkspaceDialog({
  isOpen,
  onSubmit,
  onClose,
  isSubmitting,
  error,
}: {
  isOpen: boolean
  onSubmit: (values: CreateWorkspaceFormValues) => void
  onClose: () => void
  isSubmitting: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateWorkspaceFormValues>({
    resolver: zodResolver(createWorkspaceFormSchema),
    defaultValues: { name: '' },
  })

  useEffect(() => {
    if (isOpen) reset({ name: '' })
  }, [isOpen, reset])

  if (!isOpen) return null

  return (
    <ModalShell
      title="Create workspace"
      eyebrow="Workspace"
      onClose={onClose}
      isCloseDisabled={isSubmitting}
    >
      <form className="modal-form" onSubmit={handleSubmit(onSubmit)} noValidate>
        <label className="app-field">
          Name
          <input autoFocus placeholder="Product team" {...register('name')} />
        </label>
        {errors.name?.message ? (
          <InlineNotice tone="warning">{errors.name.message}</InlineNotice>
        ) : null}
        {error ? <ErrorState error={error} /> : null}
        <div className="modal-actions">
          <Button type="button" variant="ghost" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? <Loader2 className="auth-spin" /> : <Plus />}
            Create and switch
          </Button>
        </div>
      </form>
    </ModalShell>
  )
}

function WorkspaceInvitationsDialog({
  isOpen,
  currentWorkspace,
  invitations,
  latestInvitationLink,
  isLoading,
  isMutating,
  error,
  onSubmit,
  onReissue,
  onRevoke,
  onClose,
}: {
  isOpen: boolean
  currentWorkspace: AuthWorkspace | null
  invitations: WorkspaceInvitation[]
  latestInvitationLink: string | null
  isLoading: boolean
  isMutating: boolean
  error: Error | null
  onSubmit: (values: CreateWorkspaceInvitationFormValues) => Promise<void>
  onReissue: (invitationId: string) => void
  onRevoke: (invitationId: string) => void
  onClose: () => void
}) {
  const [copiedInvitationLink, setCopiedInvitationLink] = useState<string | null>(null)
  const allowedRoles = invitationRolesFor(currentWorkspace?.role)
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateWorkspaceInvitationFormValues>({
    resolver: zodResolver(createWorkspaceInvitationFormSchema),
    defaultValues: { email: '', role: allowedRoles[0] ?? 'MEMBER' },
  })

  useEffect(() => {
    if (isOpen) {
      reset({ email: '', role: allowedRoles[0] ?? 'MEMBER' })
    }
  }, [allowedRoles, isOpen, reset])

  if (!isOpen) return null

  async function copyLatestLink() {
    if (!latestInvitationLink) return
    await navigator.clipboard.writeText(latestInvitationLink)
    setCopiedInvitationLink(latestInvitationLink)
  }

  async function submitInvitation(values: CreateWorkspaceInvitationFormValues) {
    try {
      await onSubmit(values)
      reset({ email: '', role: allowedRoles[0] ?? 'MEMBER' })
    } catch {
      // The mutation error is rendered below so the form values remain available.
    }
  }

  return (
    <ModalShell
      title="Workspace invitations"
      eyebrow={currentWorkspace?.name ?? 'Workspace'}
      onClose={onClose}
      variant="members"
      isCloseDisabled={isMutating}
    >
      <form
        className="workspace-invitation-form"
        onSubmit={handleSubmit(submitInvitation)}
        noValidate
      >
        <label className="app-field">
          Email
          <input
            type="email"
            placeholder="teammate@example.com"
            disabled={isMutating}
            {...register('email')}
          />
        </label>
        <label className="app-field">
          Role
          <select disabled={isMutating} {...register('role')}>
            {allowedRoles.map((role) => (
              <option key={role} value={role}>{titleCaseWorkspaceRole(role)}</option>
            ))}
          </select>
        </label>
        <Button type="submit" disabled={isMutating}>
          {isMutating ? <Loader2 className="auth-spin" /> : <Send />}
          Create invite
        </Button>
      </form>
      {errors.email?.message ? <InlineNotice tone="warning">{errors.email.message}</InlineNotice> : null}
      {errors.role?.message ? <InlineNotice tone="warning">{errors.role.message}</InlineNotice> : null}

      {latestInvitationLink ? (
        <div className="workspace-invitation-link">
          <span>Share this link</span>
          <div>
            <input readOnly value={latestInvitationLink} aria-label="Workspace invitation link" />
            <Button type="button" variant="outline" onClick={copyLatestLink}>
              {copiedInvitationLink === latestInvitationLink ? <Check /> : <Copy />}
              {copiedInvitationLink === latestInvitationLink ? 'Copied' : 'Copy'}
            </Button>
          </div>
          <small>For security, the link is shown only for this newly issued token.</small>
        </div>
      ) : null}

      {error ? <ErrorState error={error} /> : null}
      <div className="workspace-invitation-list">
        <div className="workspace-invitation-list-header">
          <strong>Invitation history</strong>
          <span>{invitations.length}</span>
        </div>
        {isLoading ? <InlineState>Loading invitations.</InlineState> : null}
        {!isLoading && invitations.length === 0 ? (
          <InlineState>No invitations yet.</InlineState>
        ) : null}
        {invitations.map((invitation) => {
          const canManageRole = canManageInvitationRole(currentWorkspace?.role, invitation.role)
          const canReissue = invitation.status === 'PENDING' || invitation.status === 'EXPIRED'
          const canRevoke = invitation.status !== 'ACCEPTED' && invitation.status !== 'REVOKED'
          return (
            <div className="workspace-invitation-row" key={invitation.id}>
              <div>
                <strong>{invitation.email}</strong>
                <span>
                  {titleCaseWorkspaceRole(invitation.role)} / {invitation.status.toLowerCase()} / expires {formatDate(invitation.expiresAt)}
                </span>
              </div>
              <div className="workspace-invitation-row-actions">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  title="Reissue invitation"
                  aria-label={`Reissue invitation for ${invitation.email}`}
                  disabled={!canManageRole || !canReissue || isMutating}
                  onClick={() => onReissue(invitation.id)}
                >
                  <RefreshCw />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  title="Revoke invitation"
                  aria-label={`Revoke invitation for ${invitation.email}`}
                  disabled={!canManageRole || !canRevoke || isMutating}
                  onClick={() => {
                    if (window.confirm(`Revoke the invitation for ${invitation.email}?`)) {
                      onRevoke(invitation.id)
                    }
                  }}
                >
                  <Trash2 />
                </Button>
              </div>
            </div>
          )
        })}
      </div>
    </ModalShell>
  )
}

function CreateProjectDialog({
  isOpen,
  onSubmit,
  onClose,
  canCreateProject,
  isSubmitting,
  error,
}: {
  isOpen: boolean
  onSubmit: (values: CreateProjectFormValues) => void
  onClose: () => void
  canCreateProject: boolean
  isSubmitting: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<CreateProjectFormValues>({
    resolver: zodResolver(createProjectFormSchema),
    defaultValues: {
      name: '',
      description: '',
    },
  })
  const projectName = useWatch({ control, name: 'name' }) ?? ''

  useEffect(() => {
    if (isOpen) {
      reset({
        name: '',
        description: '',
      })
    }
  }, [isOpen, reset])

  if (!isOpen) {
    return null
  }

  return (
    <ModalShell title="Create project" eyebrow="Project" onClose={onClose}>
      <form className="modal-form" onSubmit={handleSubmit(onSubmit)} noValidate>
        <label className="app-field">
          Name
          <input
            autoFocus
            placeholder="Mobile launch"
            disabled={!canCreateProject}
            {...register('name')}
          />
        </label>
        {errors.name?.message ? <InlineNotice tone="warning">{errors.name.message}</InlineNotice> : null}
        <label className="app-field">
          Description
          <textarea
            placeholder="Optional project notes"
            rows={4}
            disabled={!canCreateProject}
            {...register('description')}
          />
        </label>
        {errors.description?.message ? (
          <InlineNotice tone="warning">{errors.description.message}</InlineNotice>
        ) : null}
        {error ? <ErrorState error={error} /> : null}
        <div className="modal-actions">
          <Button type="button" variant="ghost" onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={!canCreateProject || !projectName.trim() || isSubmitting}
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            Create project
          </Button>
        </div>
      </form>
    </ModalShell>
  )
}

function CreateIssueDialog({
  isOpen,
  selectedProject,
  projectMembers,
  projectWorkflowStates,
  projectLabels,
  initialWorkflowStateId,
  initialTitle,
  initialAssigneeUserId,
  onCreateLabel,
  onSubmit,
  onClose,
  isSubmitting,
  isCreatingLabel,
  error,
  createLabelError,
}: {
  isOpen: boolean
  selectedProject: Project | null
  projectMembers: ProjectMember[]
  projectWorkflowStates: ProjectWorkflowState[]
  projectLabels: ProjectLabel[]
  initialWorkflowStateId: string
  initialTitle: string
  initialAssigneeUserId: string
  onCreateLabel: (values: CreateProjectLabelFormValues) => Promise<ProjectLabel | null>
  onSubmit: (values: CreateIssueFormValues) => void
  onClose: () => void
  isSubmitting: boolean
  isCreatingLabel: boolean
  error: Error | null
  createLabelError: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    control,
    getValues,
    formState: { errors },
  } = useForm<CreateIssueFormValues>({
    resolver: zodResolver(createIssueFormSchema),
    defaultValues: {
      title: initialTitle,
      description: '',
      workflowStateId: initialWorkflowStateId,
      priority: '',
      labelIds: [],
      assigneeUserId: initialAssigneeUserId,
      dueDate: '',
    },
  })
  const {
    register: registerLabel,
    handleSubmit: handleLabelSubmit,
    reset: resetLabelForm,
    control: labelControl,
    formState: { errors: labelErrors },
  } = useForm<CreateProjectLabelFormValues>({
    resolver: zodResolver(createProjectLabelFormSchema),
    defaultValues: {
      name: '',
      color: DEFAULT_LABEL_COLOR,
    },
  })
  const issueTitle = useWatch({ control, name: 'title' }) ?? ''
  const issueWorkflowStateId = useWatch({ control, name: 'workflowStateId' }) ?? ''
  const issueLabelIds = useWatch({ control, name: 'labelIds' }) ?? []
  const newLabelName = useWatch({ control: labelControl, name: 'name' }) ?? ''
  const selectedWorkflowState = projectWorkflowStates.find(
    (workflowState) => workflowState.id === issueWorkflowStateId,
  )

  useEffect(() => {
    if (isOpen) {
      reset({
        title: initialTitle,
        description: '',
        workflowStateId: initialWorkflowStateId,
        priority: '',
        labelIds: [],
        assigneeUserId: initialAssigneeUserId,
        dueDate: '',
      })
      resetLabelForm({
        name: '',
        color: DEFAULT_LABEL_COLOR,
      })
    }
  }, [
    initialAssigneeUserId,
    initialTitle,
    initialWorkflowStateId,
    isOpen,
    reset,
    resetLabelForm,
  ])

  function handleIssueLabelToggle(labelId: string) {
    const nextLabelIds = issueLabelIds.includes(labelId)
      ? issueLabelIds.filter((currentLabelId) => currentLabelId !== labelId)
      : [...issueLabelIds, labelId]

    setValue('labelIds', nextLabelIds, {
      shouldDirty: true,
      shouldTouch: true,
      shouldValidate: true,
    })
  }

  async function submitNewLabel(values: CreateProjectLabelFormValues) {
    try {
      const label = await onCreateLabel(values)
      if (!label) {
        return
      }

      const currentLabelIds = getValues('labelIds') ?? []
      setValue(
        'labelIds',
        currentLabelIds.includes(label.id) ? currentLabelIds : [...currentLabelIds, label.id],
        {
          shouldDirty: true,
          shouldTouch: true,
          shouldValidate: true,
        },
      )
      resetLabelForm({
        name: '',
        color: DEFAULT_LABEL_COLOR,
      })
    } catch {
      // The create-label mutation error is rendered in the label section.
    }
  }

  if (!isOpen) {
    return null
  }

  return (
    <ModalShell
      title="New issue"
      eyebrow={selectedProject?.name ?? 'No project selected'}
      onClose={onClose}
      variant="issue"
    >
      <form
        className="issue-modal-form"
        onSubmit={handleSubmit((values) => onSubmit(values))}
        noValidate
      >
        <input
          autoFocus
          className="issue-modal-title"
          placeholder="Issue title"
          disabled={!selectedProject}
          {...register('title')}
        />
        {errors.title?.message ? <InlineNotice tone="warning">{errors.title.message}</InlineNotice> : null}
        <textarea
          className="issue-modal-description"
          placeholder="Add description..."
          rows={5}
          disabled={!selectedProject}
          {...register('description')}
        />
        {errors.description?.message ? (
          <InlineNotice tone="warning">{errors.description.message}</InlineNotice>
        ) : null}
        <div className="issue-modal-chip-row" aria-label="Issue properties">
          <label className="issue-modal-chip issue-modal-chip-select">
            <StatusIcon status={selectedWorkflowState?.category ?? 'TODO'} />
            <select
              disabled={!selectedProject || projectWorkflowStates.length === 0}
              aria-label="Status"
              {...register('workflowStateId')}
            >
              {projectWorkflowStates.map((workflowState) => (
                <option key={workflowState.id} value={workflowState.id}>
                  {workflowState.name}
                </option>
              ))}
            </select>
          </label>
          <label className="issue-modal-chip issue-modal-chip-select">
            <Flag aria-hidden="true" />
            <select
              disabled={!selectedProject}
              aria-label="Priority"
              {...register('priority')}
            >
              <option value="">Priority</option>
              {ISSUE_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {PRIORITY_LABELS[priority]}
                </option>
              ))}
            </select>
          </label>
          <label className="issue-modal-chip issue-modal-chip-select">
            <UserCircle aria-hidden="true" />
            <select
              disabled={!selectedProject || projectMembers.length === 0}
              aria-label="Assignee"
              {...register('assigneeUserId')}
            >
              <option value="">Assignee</option>
              {projectMembers.map((member) => (
                <option key={member.id} value={member.userId}>
                  {member.displayName || member.email}
                </option>
              ))}
            </select>
          </label>
          <label className="issue-modal-chip issue-modal-chip-date">
            <CalendarDays aria-hidden="true" />
            <input
              type="date"
              disabled={!selectedProject}
              aria-label="Due date"
              {...register('dueDate')}
            />
          </label>
          <span className="issue-modal-chip">
            <FolderKanban aria-hidden="true" />
            {selectedProject?.name ?? 'Project'}
          </span>
          <span className="issue-modal-chip issue-modal-chip-muted">
            <MoreHorizontal aria-hidden="true" />
          </span>
        </div>
        <div className="issue-modal-label-section">
          <div className="issue-modal-section-title">
            <Tag aria-hidden="true" />
            Labels
          </div>
          {projectLabels.length === 0 ? (
            <InlineState>No labels yet.</InlineState>
          ) : (
            <div className="label-toggle-list label-toggle-list-inline">
              {projectLabels.map((label) => (
                <label className="label-toggle" key={label.id}>
                  <input
                    type="checkbox"
                    checked={issueLabelIds.includes(label.id)}
                    onChange={() => handleIssueLabelToggle(label.id)}
                    disabled={!selectedProject || isSubmitting}
                  />
                  <LabelBadge label={label} />
                </label>
              ))}
            </div>
          )}
          <div className="issue-modal-label-create">
            <label className="app-field">
              New label
              <input
                placeholder="Bug"
                disabled={!selectedProject || isCreatingLabel}
                {...registerLabel('name')}
              />
            </label>
            <label className="app-field app-field-color">
              Color
              <input
                type="color"
                disabled={!selectedProject || isCreatingLabel}
                {...registerLabel('color')}
              />
            </label>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                void handleLabelSubmit(submitNewLabel)()
              }}
              disabled={!selectedProject || !newLabelName.trim() || isCreatingLabel}
            >
              {isCreatingLabel ? (
                <Loader2 aria-hidden="true" className="auth-spin" />
              ) : (
                <Plus aria-hidden="true" />
              )}
              Add label
            </Button>
          </div>
          {labelErrors.name?.message ? (
            <InlineNotice tone="warning">{labelErrors.name.message}</InlineNotice>
          ) : null}
          {labelErrors.color?.message ? (
            <InlineNotice tone="warning">{labelErrors.color.message}</InlineNotice>
          ) : null}
          {createLabelError ? <ErrorState error={createLabelError} /> : null}
        </div>
        {error ? <ErrorState error={error} /> : null}
        <div className="issue-modal-footer">
          <span className="app-state">{selectedProject?.name ?? 'No project selected'}</span>
          <Button
            type="submit"
            disabled={!selectedProject || !issueTitle.trim() || isSubmitting}
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            Create issue
          </Button>
        </div>
      </form>
    </ModalShell>
  )
}

function ProjectWorkflowDialog({
  isOpen,
  selectedProject,
  workflowStates,
  onSubmit,
  onUpdate,
  onReorder,
  onClose,
  canCreateWorkflowStates,
  isLoadingWorkflowStates,
  workflowStatesError,
  isSubmitting,
  isUpdating,
  isReordering,
  error,
}: {
  isOpen: boolean
  selectedProject: Project | null
  workflowStates: ProjectWorkflowState[]
  onSubmit: (values: CreateProjectWorkflowStateFormValues) => Promise<ProjectWorkflowState | null>
  onUpdate: (
    workflowStateId: string,
    values: UpdateProjectWorkflowStateFormValues,
  ) => Promise<ProjectWorkflowState | null>
  onReorder: (workflowStateId: string, direction: -1 | 1) => Promise<void>
  onClose: () => void
  canCreateWorkflowStates: boolean
  isLoadingWorkflowStates: boolean
  workflowStatesError: Error | null
  isSubmitting: boolean
  isUpdating: boolean
  isReordering: boolean
  error: Error | null
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<CreateProjectWorkflowStateFormValues>({
    resolver: zodResolver(createProjectWorkflowStateFormSchema),
    defaultValues: {
      name: '',
      category: 'IN_PROGRESS',
    },
  })
  const workflowStateName = useWatch({ control, name: 'name' }) ?? ''
  const isMutatingWorkflowState = isSubmitting || isUpdating || isReordering

  useEffect(() => {
    if (isOpen) {
      reset({
        name: '',
        category: 'IN_PROGRESS',
      })
    }
  }, [isOpen, reset])

  async function submitWorkflowState(values: CreateProjectWorkflowStateFormValues) {
    try {
      await onSubmit(values)
      reset({
        name: '',
        category: 'IN_PROGRESS',
      })
    } catch {
      // The mutation error is rendered below so the draft status stays available.
    }
  }

  if (!isOpen) {
    return null
  }

  return (
    <ModalShell
      title="Workflow statuses"
      eyebrow={selectedProject?.name ?? 'Project'}
      onClose={onClose}
      variant="members"
      isCloseDisabled={isMutatingWorkflowState}
    >
      <div className="project-members-dialog">
        {isLoadingWorkflowStates ? <InlineState>Loading workflow statuses.</InlineState> : null}
        {workflowStatesError ? <ErrorState error={workflowStatesError} /> : null}
        {!isLoadingWorkflowStates && !workflowStatesError ? (
          <div className="project-member-list" aria-label="Workflow statuses">
            {workflowStates.map((workflowState, index) => (
              <WorkflowStateRow
                key={workflowState.id}
                workflowState={workflowState}
                canManageWorkflowStates={canCreateWorkflowStates}
                canMoveUp={index > 0}
                canMoveDown={index < workflowStates.length - 1}
                isMutating={isMutatingWorkflowState}
                onUpdate={onUpdate}
                onReorder={onReorder}
              />
            ))}
          </div>
        ) : null}

        <section className="project-member-add-section">
          <div className="project-member-add-heading">
            <h3>Add status</h3>
            <span className="app-state">{workflowStates.length} statuses</span>
          </div>
          {!canCreateWorkflowStates ? (
            <InlineNotice>Only project owners can manage workflow statuses.</InlineNotice>
          ) : (
            <form
              className="project-member-add-form"
              onSubmit={handleSubmit(submitWorkflowState)}
              noValidate
            >
              <label className="app-field">
                Name
                <input
                  placeholder="Review"
                  disabled={isMutatingWorkflowState}
                  {...register('name')}
                />
              </label>
              <label className="app-field">
                Category
                <select disabled={isMutatingWorkflowState} {...register('category')}>
                  {WORKFLOW_STATE_CATEGORIES.map((category) => (
                    <option key={category} value={category}>
                      {formatStatus(category)}
                    </option>
                  ))}
                </select>
              </label>
              {errors.name?.message ? (
                <InlineNotice tone="warning">{errors.name.message}</InlineNotice>
              ) : null}
              {errors.category?.message ? (
                <InlineNotice tone="warning">{errors.category.message}</InlineNotice>
              ) : null}
              <Button type="submit" disabled={!workflowStateName.trim() || isMutatingWorkflowState}>
                {isSubmitting ? (
                  <Loader2 aria-hidden="true" className="auth-spin" />
                ) : (
                  <Plus aria-hidden="true" />
                )}
                Add status
              </Button>
            </form>
          )}
          {error ? <ErrorState error={error} /> : null}
        </section>
      </div>
    </ModalShell>
  )
}

function WorkflowStateRow({
  workflowState,
  canManageWorkflowStates,
  canMoveUp,
  canMoveDown,
  isMutating,
  onUpdate,
  onReorder,
}: {
  workflowState: ProjectWorkflowState
  canManageWorkflowStates: boolean
  canMoveUp: boolean
  canMoveDown: boolean
  isMutating: boolean
  onUpdate: (
    workflowStateId: string,
    values: UpdateProjectWorkflowStateFormValues,
  ) => Promise<ProjectWorkflowState | null>
  onReorder: (workflowStateId: string, direction: -1 | 1) => Promise<void>
}) {
  const [isEditing, setIsEditing] = useState(false)
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<UpdateProjectWorkflowStateFormValues>({
    resolver: zodResolver(updateProjectWorkflowStateFormSchema),
    defaultValues: {
      name: workflowState.name,
      category: toWorkflowStateCategoryInput(workflowState.category),
    },
  })

  useEffect(() => {
    reset({
      name: workflowState.name,
      category: toWorkflowStateCategoryInput(workflowState.category),
    })
  }, [reset, workflowState.category, workflowState.name])

  async function submitWorkflowState(values: UpdateProjectWorkflowStateFormValues) {
    try {
      await onUpdate(workflowState.id, values)
      setIsEditing(false)
    } catch {
      // The mutation error is rendered in the dialog footer so the edited row stays open.
    }
  }

  function cancelEdit() {
    reset({
      name: workflowState.name,
      category: toWorkflowStateCategoryInput(workflowState.category),
    })
    setIsEditing(false)
  }

  return (
    <div className="project-member-row project-workflow-row">
      <span className="workspace-avatar project-member-avatar" aria-hidden="true">
        <StatusIcon status={workflowState.category} />
      </span>
      {isEditing ? (
        <form
          className="workflow-state-edit-form"
          onSubmit={handleSubmit(submitWorkflowState)}
          noValidate
        >
          <label className="app-field">
            Name
            <input disabled={isMutating} {...register('name')} />
          </label>
          <label className="app-field">
            Category
            <select disabled={isMutating} {...register('category')}>
              {WORKFLOW_STATE_CATEGORIES.map((category) => (
                <option key={category} value={category}>
                  {formatStatus(category)}
                </option>
              ))}
            </select>
          </label>
          {errors.name?.message ? (
            <InlineNotice tone="warning">{errors.name.message}</InlineNotice>
          ) : null}
          {errors.category?.message ? (
            <InlineNotice tone="warning">{errors.category.message}</InlineNotice>
          ) : null}
          <span className="workflow-state-edit-actions">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={cancelEdit}
              disabled={isMutating}
              aria-label="Cancel editing status"
              title="Cancel"
            >
              <X aria-hidden="true" />
            </Button>
            <Button
              type="submit"
              size="icon-sm"
              disabled={!isDirty || isMutating}
              aria-label="Save workflow status"
              title="Save"
            >
              {isMutating ? (
                <Loader2 aria-hidden="true" className="auth-spin" />
              ) : (
                <Check aria-hidden="true" />
              )}
            </Button>
          </span>
        </form>
      ) : (
        <>
          <span className="project-member-person">
            <strong>{workflowState.name}</strong>
            <small>{formatStatus(workflowState.category)}</small>
          </span>
          <span className="project-member-meta workflow-state-actions">
            <span className="project-member-status">#{workflowState.position}</span>
            {canManageWorkflowStates ? (
              <>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => void onReorder(workflowState.id, -1)}
                  disabled={!canMoveUp || isMutating}
                  aria-label={`Move ${workflowState.name} up`}
                  title="Move up"
                >
                  <ArrowUp aria-hidden="true" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => void onReorder(workflowState.id, 1)}
                  disabled={!canMoveDown || isMutating}
                  aria-label={`Move ${workflowState.name} down`}
                  title="Move down"
                >
                  <ArrowDown aria-hidden="true" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => setIsEditing(true)}
                  disabled={isMutating}
                  aria-label={`Edit ${workflowState.name}`}
                  title="Edit"
                >
                  <Pencil aria-hidden="true" />
                </Button>
              </>
            ) : null}
          </span>
        </>
      )}
    </div>
  )
}

function ProjectMembersDialog({
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

function ModalShell({
  title,
  eyebrow,
  children,
  onClose,
  variant = 'default',
  isCloseDisabled = false,
}: {
  title: string
  eyebrow: string
  children: ReactNode
  onClose: () => void
  variant?: 'default' | 'issue' | 'members'
  isCloseDisabled?: boolean
}) {
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !isCloseDisabled) {
        onClose()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [isCloseDisabled, onClose])

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !isCloseDisabled) {
          onClose()
        }
      }}
    >
      <section className="modal-panel" data-variant={variant} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <div>
            <p className="breadcrumb-line">{eyebrow}</p>
            <h2>{title}</h2>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={onClose}
            aria-label="Close"
            disabled={isCloseDisabled}
          >
            <X aria-hidden="true" />
          </Button>
        </header>
        {children}
      </section>
    </div>
  )
}

function BreadcrumbLine({ items }: { items: string[] }) {
  return (
    <p className="breadcrumb-line">
      {items.map((item, index) => (
        <span key={`${item}-${index}`}>
          {index > 0 ? <span aria-hidden="true">/</span> : null}
          {item}
        </span>
      ))}
    </p>
  )
}

function StatusIcon({ status }: { status: IssueStatus }) {
  if (status === 'DONE') {
    return <CheckCircle2 aria-hidden="true" className="status-icon status-icon-done" />
  }

  if (status === 'IN_PROGRESS') {
    return <CircleDot aria-hidden="true" className="status-icon status-icon-progress" />
  }

  if (status === 'ARCHIVED') {
    return <Circle aria-hidden="true" className="status-icon status-icon-muted" />
  }

  return <Circle aria-hidden="true" className="status-icon" />
}

function PriorityBadge({ priority }: { priority?: IssuePriority | null }) {
  if (!priority) {
    return <span className="priority-badge priority-badge-empty">No priority</span>
  }

  return (
    <span className="priority-badge">
      <Flag aria-hidden="true" />
      {formatPriority(priority)}
    </span>
  )
}

function LabelBadge({ label }: { label: ProjectLabel }) {
  return (
    <span className="label-badge">
      <span className="label-swatch" style={{ backgroundColor: label.color }} aria-hidden="true" />
      {label.name}
    </span>
  )
}

function InlineState({ children }: { children: ReactNode }) {
  return <p className="app-state">{children}</p>
}

function InlineNotice({
  children,
  tone = 'default',
}: {
  children: ReactNode
  tone?: 'default' | 'warning'
}) {
  return (
    <p className="inline-notice" data-tone={tone}>
      {children}
    </p>
  )
}

function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty-state">
      <Building2 aria-hidden="true" />
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  )
}

function ErrorState({ error }: { error: Error }) {
  return <p className="app-error">{getErrorMessage(error)}</p>
}

function ProjectMemberMutationErrorState({
  error,
  action,
}: {
  error: Error
  action: 'add' | 'update' | 'remove'
}) {
  return <p className="app-error">{getProjectMemberMutationErrorMessage(error, action)}</p>
}


function statusForIcon(issue: IssueSummary | IssueDetail) {
  return issue.status === 'ARCHIVED' ? 'ARCHIVED' : issue.workflowState.category
}

function issueViewModeFromSearchParams(searchParams: URLSearchParams): IssueViewMode {
  return searchParams.get('layout') === 'list' ? 'LIST' : 'BOARD'
}

function boardIssueViewFromSearchParams(searchParams: URLSearchParams): BoardIssueView {
  const view = searchParams.get('view')
  if (view === 'mine') {
    return 'MINE'
  }
  if (view === 'unassigned') {
    return 'UNASSIGNED'
  }
  return 'ALL'
}

function analyticsRangeFromSearchParams(
  searchParams: URLSearchParams,
): AnalyticsRangeDays {
  const range = searchParams.get('range')
  if (range === '7') {
    return 7
  }
  if (range === '90') {
    return 90
  }
  return 30
}

function normalizeAppSearchParams(
  searchParams: URLSearchParams,
  isAnalyticsRoute: boolean,
) {
  const normalized = normalizeWorkViewSearchParams(searchParams)
  if (!isAnalyticsRoute) {
    normalized.delete('range')
    return normalized
  }

  const range = normalized.get('range')
  if (range !== '7' && range !== '90') {
    normalized.delete('range')
  }

  return normalized
}

function normalizeWorkViewSearchParams(searchParams: URLSearchParams) {
  const normalized = new URLSearchParams(searchParams)
  if (normalized.get('layout') !== 'list') {
    normalized.delete('layout')
  }

  const view = normalized.get('view')
  if (view !== 'mine' && view !== 'unassigned') {
    normalized.delete('view')
  }

  return normalized
}

function issueViewSearchParams(searchParams: URLSearchParams) {
  const normalized = normalizeWorkViewSearchParams(searchParams)
  normalized.delete('range')
  return normalized
}

function workViewSearchParams(
  searchParams: URLSearchParams,
  layout: IssueViewMode,
  view: BoardIssueView,
) {
  const next = new URLSearchParams(searchParams)
  if (layout === 'LIST') {
    next.set('layout', 'list')
  } else {
    next.delete('layout')
  }

  if (view === 'MINE') {
    next.set('view', 'mine')
  } else if (view === 'UNASSIGNED') {
    next.set('view', 'unassigned')
  } else {
    next.delete('view')
  }

  next.delete('range')

  return next
}

function analyticsSearchParams(
  searchParams: URLSearchParams,
  rangeDays: AnalyticsRangeDays,
) {
  const next = normalizeWorkViewSearchParams(searchParams)
  if (rangeDays === 30) {
    next.delete('range')
  } else {
    next.set('range', String(rangeDays))
  }

  return next
}

function pathWithSearchParams(path: string, searchParams: URLSearchParams | string) {
  const searchString =
    typeof searchParams === 'string'
      ? searchParams.replace(/^\?/, '')
      : searchParams.toString()
  return searchString ? `${path}?${searchString}` : path
}

function projectPath(workspaceId: string, projectId: string) {
  return `/app/workspaces/${workspaceId}/projects/${projectId}`
}

function projectAnalyticsPath(workspaceId: string, projectId: string) {
  return `${projectPath(workspaceId, projectId)}/analytics`
}

function issuePath(workspaceId: string, projectId: string, issueId: string) {
  return `${projectPath(workspaceId, projectId)}/issues/${issueId}`
}

function isProjectAnalyticsPath(pathname: string) {
  return pathname.endsWith('/analytics')
}

function getInitials(value: string) {
  return value
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

function getErrorMessage(error: Error) {
  if (error instanceof ApiError) {
    return error.message
  }

  return 'Unable to load this workspace data.'
}

function getProjectMemberMutationErrorMessage(
  error: Error,
  action: 'add' | 'update' | 'remove',
) {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return action === 'add'
        ? 'This member is already active in the project.'
        : 'A project must have at least one active owner.'
    }

    if (error.status === 403) {
      return 'You do not have permission to manage project members.'
    }

    if (error.status === 404) {
      return action === 'add'
        ? 'This project or workspace member is no longer available.'
        : 'This project member is no longer available.'
    }

    return error.message
  }

  if (action === 'update') {
    return 'Unable to update this project member.'
  }

  if (action === 'remove') {
    return 'Unable to remove this project member.'
  }

  return 'Unable to add this project member.'
}

function formatStatus(status: string) {
  if (isKnownStatus(status)) {
    return STATUS_LABELS[status]
  }

  return status
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

function formatPriority(priority: string) {
  if (isKnownPriority(priority)) {
    return PRIORITY_LABELS[priority]
  }

  return formatStatus(priority)
}

function formatProjectRole(role: string) {
  return formatStatus(role)
}

function isKnownStatus(status: string): status is (typeof ISSUE_STATUSES)[number] {
  return ISSUE_STATUSES.includes(status as (typeof ISSUE_STATUSES)[number])
}

function toWorkflowStateCategoryInput(
  category: WorkflowStateCategory,
): (typeof WORKFLOW_STATE_CATEGORIES)[number] {
  return WORKFLOW_STATE_CATEGORIES.includes(category as (typeof WORKFLOW_STATE_CATEGORIES)[number])
    ? (category as (typeof WORKFLOW_STATE_CATEGORIES)[number])
    : 'IN_PROGRESS'
}

function isKnownPriority(priority: string): priority is (typeof ISSUE_PRIORITIES)[number] {
  return ISSUE_PRIORITIES.includes(priority as (typeof ISSUE_PRIORITIES)[number])
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function invitationRolesFor(role: string | undefined): InvitableWorkspaceRole[] {
  if (role === 'OWNER') return OWNER_INVITATION_ROLES
  if (role === 'ADMIN') return ADMIN_INVITATION_ROLES
  return []
}

function canManageInvitationRole(
  actorRole: string | undefined,
  invitationRole: InvitableWorkspaceRole,
) {
  return invitationRolesFor(actorRole).includes(invitationRole)
}

function titleCaseWorkspaceRole(role: string) {
  return role.charAt(0) + role.slice(1).toLowerCase()
}

function requireRefreshToken() {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new Error('Your session cannot switch workspaces. Sign in again.')
  }
  return refreshToken
}

function invitationUrl(invitation: WorkspaceInvitationCreated) {
  return `${window.location.origin}/invite/${invitation.token}`
}

function formatDateOnly(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${value}T00:00:00`))
}

function formatActivity(activity: ActivityEvent) {
  const actor = activity.actor.displayName || activity.actor.email
  const metadata = activity.metadata ?? {}

  switch (activity.eventType) {
    case 'PROJECT_CREATED':
      return `${actor} created project ${readMetadata(metadata, 'projectName') ?? 'this project'}`
    case 'ISSUE_CREATED':
      return `${actor} created this issue`
    case 'COMMENT_CREATED':
      return `${actor} commented`
    case 'ISSUE_STATUS_CHANGED': {
      const fromStatus = readMetadata(metadata, 'fromStatus')
      const toStatus = readMetadata(metadata, 'toStatus')
      if (fromStatus && toStatus) {
        return `${actor} changed status from ${formatStatus(fromStatus)} to ${formatStatus(toStatus)}`
      }

      return `${actor} changed issue status`
    }
    case 'ISSUE_TITLE_CHANGED':
      return `${actor} renamed this issue`
    case 'ISSUE_PRIORITY_CHANGED': {
      const fromPriority = readMetadata(metadata, 'fromPriority')
      const toPriority = readMetadata(metadata, 'toPriority')
      return `${actor} changed priority from ${formatOptionalPriority(fromPriority)} to ${formatOptionalPriority(toPriority)}`
    }
    case 'ISSUE_ASSIGNEE_CHANGED': {
      const fromAssignee = readMetadata(metadata, 'fromAssigneeName')
      const toAssignee = readMetadata(metadata, 'toAssigneeName')
      return `${actor} changed assignee from ${fromAssignee ?? 'Unassigned'} to ${toAssignee ?? 'Unassigned'}`
    }
    case 'ISSUE_DUE_DATE_CHANGED': {
      const fromDueDate = readMetadata(metadata, 'fromDueDate')
      const toDueDate = readMetadata(metadata, 'toDueDate')
      return `${actor} changed due date from ${formatOptionalDueDate(fromDueDate)} to ${formatOptionalDueDate(toDueDate)}`
    }
    default:
      return `${actor} recorded ${formatStatus(activity.eventType)}`
  }
}

function formatOptionalPriority(priority: string | undefined) {
  return priority ? formatPriority(priority) : 'No priority'
}

function formatOptionalDueDate(dueDate: string | undefined) {
  return dueDate ? formatDateOnly(dueDate) : 'No due date'
}

function readMetadata(metadata: Record<string, unknown>, key: string) {
  const value = metadata[key]
  return typeof value === 'string' ? value : undefined
}
