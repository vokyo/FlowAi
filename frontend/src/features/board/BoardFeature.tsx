import { useEffect, useState } from 'react'
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
import { useForm, useWatch } from 'react-hook-form'
import {
  CalendarDays,
  GripVertical,
  Loader2,
  Maximize2,
  MessageSquare,
  Plus,
  UserCircle,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { CursorPage } from '@/api/pagination'
import {
  type BoardColumn,
  type IssueSummary,
  type ProjectBoard,
  type ProjectWorkflowState,
} from '@/work/work-api'
import {
  boardEmptyColumnLabel,
  buildOptimisticBoard,
  findBoardIssue,
  kanbanColumnId,
  type BoardIssueView,
} from '@/work/board-utils'
import {
  quickCreateIssueFormSchema,
  type CreateIssueDialogSeed,
  type KanbanReorder,
  type QuickCreateIssueFormValues,
  type QuickCreateIssueMutationVariables,
} from '@/features/project-shell/project-model'
import { formatDateOnly, getErrorMessage } from '@/features/project-shell/display-utils'
import {
  ErrorState,
  InlineNotice,
  LabelBadge,
  PriorityBadge,
  StatusIcon,
} from '@/features/project-shell/feature-ui'
import { useBoardColumnPagination } from './useBoardColumnPagination'

type KanbanDragData = {
  type: 'column' | 'issue'
  workflowStateId: string
}

export function BoardFeature({
  board,
  completeBoard,
  workspaceId,
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
  onBoardColumnPageLoaded,
}: {
  board: ProjectBoard
  completeBoard: ProjectBoard
  workspaceId: string
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
  onBoardColumnPageLoaded: (
    workflowStateId: string,
    page: CursorPage<IssueSummary>,
  ) => void
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
  const isSortingEnabled = true
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
      completeBoard,
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
                projectId={board.projectId}
                workspaceId={workspaceId}
                onPageLoaded={onBoardColumnPageLoaded}
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
  projectId,
  workspaceId,
  onPageLoaded,
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
  projectId: string
  workspaceId: string
  onPageLoaded: (workflowStateId: string, page: CursorPage<IssueSummary>) => void
}) {
  const {
    query: columnPagesQuery,
    displayedIssues,
    nextCursor,
  } = useBoardColumnPagination({
    column,
    projectId,
    workspaceId,
    onPageLoaded,
  })
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
          items={displayedIssues.map((issue) => issue.id)}
          strategy={verticalListSortingStrategy}
        >
          {displayedIssues.map((issue) => (
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
        {displayedIssues.length === 0 && !isComposerOpen ? (
          <p className="kanban-column-empty">{emptyLabel}</p>
        ) : null}
        {nextCursor ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="kanban-load-more"
            disabled={columnPagesQuery.isFetchingNextPage || isReordering}
            onClick={() => void columnPagesQuery.fetchNextPage()}
          >
            {columnPagesQuery.isFetchingNextPage ? (
              <Loader2 className="auth-spin" aria-hidden="true" />
            ) : null}
            {columnPagesQuery.isFetchingNextPage ? 'Loading' : 'Load more'}
          </Button>
        ) : null}
        {columnPagesQuery.error ? <ErrorState error={columnPagesQuery.error} /> : null}
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
