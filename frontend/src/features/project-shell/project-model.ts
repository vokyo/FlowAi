import { z } from 'zod'
import type { ReorderIssuesRequest, ProjectBoard } from '@/work/work-api'
import type { WorkspaceRole } from '@/workspace/workspace-api'

export const ISSUE_STATUSES = ['TODO', 'IN_PROGRESS', 'DONE', 'ARCHIVED'] as const
export const WORKFLOW_STATE_CATEGORIES = ['TODO', 'IN_PROGRESS', 'DONE'] as const
export const ISSUE_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const
export type InvitableWorkspaceRole = Exclude<WorkspaceRole, 'OWNER'>
export const OWNER_INVITATION_ROLES: InvitableWorkspaceRole[] = ['ADMIN', 'MEMBER', 'GUEST']
export const ADMIN_INVITATION_ROLES: InvitableWorkspaceRole[] = ['MEMBER', 'GUEST']
export const DEFAULT_LABEL_COLOR = '#64748b'

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

export const STATUS_LABELS: Record<(typeof ISSUE_STATUSES)[number], string> = {
  TODO: 'Todo',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
  ARCHIVED: 'Archived',
}

export const PRIORITY_LABELS: Record<(typeof ISSUE_PRIORITIES)[number], string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  URGENT: 'Urgent',
}

export type CreateIssueDialogSeed = {
  title?: string
  assigneeUserId?: string | null
}

export type KanbanReorder = ReorderIssuesRequest & {
  optimisticBoard: ProjectBoard
}

export type ReorderIssueMutationVariables = {
  projectId: string
  request: ReorderIssuesRequest
  optimisticBoard: ProjectBoard
}

export type QuickCreateIssueMutationVariables = {
  projectId: string
  title: string
  workflowStateId: string
  assigneeUserId?: string
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

export const createProjectFormSchema = z.object({
  name: requiredTrimmedString('Name').max(FORM_LIMITS.projectName, 'Name is too long.'),
  description: z.string().max(FORM_LIMITS.projectDescription, 'Description is too long.'),
})

export const createWorkspaceFormSchema = z.object({
  name: requiredTrimmedString('Name').max(FORM_LIMITS.workspaceName, 'Name is too long.'),
})

export const createWorkspaceInvitationFormSchema = z.object({
  email: z.string().min(1, 'Email is required.').email('Enter a valid email address.'),
  role: z.enum(['ADMIN', 'MEMBER', 'GUEST']),
})

export const createIssueFormSchema = z.object({
  title: requiredTrimmedString('Title').max(FORM_LIMITS.issueTitle, 'Title is too long.'),
  description: z.string().max(FORM_LIMITS.issueDescription, 'Description is too long.'),
  workflowStateId: z.string(),
  priority: issuePriorityInputSchema,
  labelIds: z.array(z.string()),
  assigneeUserId: z.string(),
  dueDate: optionalDateInputSchema,
})

export const quickCreateIssueFormSchema = z.object({
  title: requiredTrimmedString('Title').max(FORM_LIMITS.issueTitle, 'Title is too long.'),
})

export const createProjectLabelFormSchema = z.object({
  name: requiredTrimmedString('Label name').max(FORM_LIMITS.labelName, 'Label name is too long.'),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/, 'Choose a valid label color.'),
})

export const createProjectWorkflowStateFormSchema = z.object({
  name: requiredTrimmedString('Status name').max(
    FORM_LIMITS.workflowStateName,
    'Status name is too long.',
  ),
  category: z.enum(WORKFLOW_STATE_CATEGORIES),
})

export const updateProjectWorkflowStateFormSchema = createProjectWorkflowStateFormSchema
export const addProjectMemberFormSchema = z.object({
  userId: requiredTrimmedString('Workspace member'),
})
export const issueContentFormSchema = z.object({
  title: requiredTrimmedString('Title').max(FORM_LIMITS.issueTitle, 'Title is too long.'),
  description: z.string().max(FORM_LIMITS.issueDescription, 'Description is too long.'),
})
export const commentFormSchema = z.object({
  body: requiredTrimmedString('Comment').max(FORM_LIMITS.commentBody, 'Comment is too long.'),
})

export type CreateProjectFormValues = z.infer<typeof createProjectFormSchema>
export type CreateWorkspaceFormValues = z.infer<typeof createWorkspaceFormSchema>
export type CreateWorkspaceInvitationFormValues = z.infer<
  typeof createWorkspaceInvitationFormSchema
>
export type CreateIssueFormValues = z.infer<typeof createIssueFormSchema>
export type QuickCreateIssueFormValues = z.infer<typeof quickCreateIssueFormSchema>
export type CreateProjectLabelFormValues = z.infer<typeof createProjectLabelFormSchema>
export type CreateProjectWorkflowStateFormValues = z.infer<
  typeof createProjectWorkflowStateFormSchema
>
export type UpdateProjectWorkflowStateFormValues = z.infer<
  typeof updateProjectWorkflowStateFormSchema
>
export type AddProjectMemberFormValues = z.infer<typeof addProjectMemberFormSchema>
export type IssueContentFormValues = z.infer<typeof issueContentFormSchema>
export type CommentFormValues = z.infer<typeof commentFormSchema>
