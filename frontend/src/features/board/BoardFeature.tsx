import {useEffect, useRef, useState} from 'react'
import {
  type Active,
  closestCenter,
  closestCorners,
  type CollisionDetection,
  DndContext,
  type DragEndEvent,
  DragOverlay,
  type DragOverEvent,
  type DragStartEvent,
  type DropAnimation,
  KeyboardSensor,
  type Over,
  PointerSensor,
  pointerWithin,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import {CSS} from '@dnd-kit/utilities'
import {zodResolver} from '@hookform/resolvers/zod'
import {useForm, useWatch} from 'react-hook-form'
import {CalendarDays, GripVertical, Loader2, Maximize2, MessageSquare, Plus, Star, UserCircle, X,} from 'lucide-react'
import {Button} from '@/components/ui/button'
import type {CursorPage} from '@/api/pagination'
import {type BoardColumn, type IssueSummary, type ProjectBoard, type ProjectWorkflowState,} from '@/api/work-api'
import {
  boardEmptyColumnLabel,
  type BoardDropTarget,
  boardIssueNeighbors,
  type BoardIssueView,
  buildOptimisticBoard,
  findBoardIssue,
  issueColumnId,
  kanbanColumnId,
  moveIssueOver,
} from '@/domain/board-utils'
import {
  type CreateIssueDialogSeed,
  type KanbanReorder,
  quickCreateIssueFormSchema,
  type QuickCreateIssueFormValues,
  type QuickCreateIssueMutationVariables,
} from '@/domain/project-model'
import {formatDateOnly, getErrorMessage} from '@/ui/display-utils'
import {ErrorState, InlineNotice, LabelBadge, PriorityBadge, StatusIcon,} from '@/ui/feature-ui'
import {useBoardColumnPagination} from './useBoardColumnPagination'

type KanbanDragData = {
  type: 'column' | 'issue'
  workflowStateId: string
}

/**
 * A board as the drag has rearranged it, and the board prop it was derived from.
 * It is shown only while `base` is still the current prop — see `shownBoard`.
 */
type BoardPreview = {
  base: ProjectBoard
  board: ProjectBoard
}

/**
 * The column under the pointer decides where the card is going; within that
 * column, the card nearest the dragged card's centre decides the slot.
 *
 * `closestCorners` over every droppable at once lets a column's own rect compete
 * with the cards of its neighbour, and once the drag preview moves a card
 * between columns — changing both columns' heights — that competition can flip
 * back and forth. Choosing the column by pointer first keeps the target steady.
 * Outside every column (a header, the gutter) and for keyboard drags, which
 * have no pointer, it falls back to `closestCorners`.
 */
const boardCollisionDetection: CollisionDetection = (args) => {
  const columns = args.droppableContainers.filter(
    (container) => (container.data.current as KanbanDragData | undefined)?.type === 'column',
  )
  const [columnHit] = pointerWithin({...args, droppableContainers: columns})
  const column = columns.find((container) => container.id === columnHit?.id)
  if (!columnHit || !column) {
    return closestCorners(args)
  }

  const {workflowStateId} = column.data.current as KanbanDragData
  const cards = args.droppableContainers.filter((container) => {
    const data = container.data.current as KanbanDragData | undefined
    return data?.type === 'issue' && data.workflowStateId === workflowStateId
  })
  const [cardHit] = closestCenter({...args, droppableContainers: cards})
  return [cardHit ?? columnHit]
}

/**
 * Lands the overlay on the card's slot — which the preview has already moved to
 * its new column — on the app's emphasised-decelerate curve, levelling out the
 * tilt it carries while dragging so the hand-off to the real card has no snap.
 */
const dropAnimation: DropAnimation = {
  duration: 200,
  easing: 'cubic-bezier(0.2, 0, 0, 1)',
  keyframes: ({transform}) => [
    {transform: CSS.Transform.toString(transform.initial), rotate: '-1.5deg'},
    {transform: CSS.Transform.toString(transform.final), rotate: '0deg'},
  ],
}

function dropTarget(active: Active, over: Over | null): BoardDropTarget | null {
  const data = over?.data.current as KanbanDragData | undefined
  if (!over || !data) {
    return null
  }
  const dragged = active.rect.current.translated
  return {
    workflowStateId: data.workflowStateId,
    issueId: data.type === 'issue' ? String(over.id) : null,
    after: Boolean(
      dragged && dragged.top + dragged.height / 2 > over.rect.top + over.rect.height / 2,
    ),
  }
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
  const [preview, setPreview] = useState<BoardPreview | null>(null)
  // The drag handlers read the preview from here rather than from state: dnd-kit
  // can deliver drag end before React has rendered the last drag-over update,
  // and the drop has to commit the arrangement that was actually on screen.
  const previewRef = useRef<BoardPreview | null>(null)
  const [composerWorkflowStateId, setComposerWorkflowStateId] = useState<string | null>(null)
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {distance: 6},
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  )
  // A preview is finished with for good once the board prop moves on from the
  // one it was built on — the optimistic update landing, or a newer fetch. It
  // is dropped, not merely hidden: a failed save rolls the cache back to the
  // *same* board object, and an identity check alone would bring the preview
  // back for as long as the rollback's refetches take. Adjusting state during
  // render is React's pattern for this; an effect would paint the stale frame
  // first.
  if (preview && preview.base !== board) {
    setPreview(null)
  }
  const shownBoard = preview?.base === board ? preview.board : board
  const activeIssue = activeIssueId ? findBoardIssue(shownBoard, activeIssueId) : null
  // The column a drag would drop into, once it is not the one the card came
  // from. dnd-kit's own `isOver` is true only while the pointer is over the
  // column's empty space, so it flickered off over every card — and, with the
  // preview putting the card into the column, off entirely.
  const dropColumnId = activeIssueId ? issueColumnId(shownBoard, activeIssueId) : null
  const originColumnId = activeIssueId ? issueColumnId(board, activeIssueId) : null
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

  function updatePreview(next: BoardPreview | null) {
    previewRef.current = next
    setPreview(next)
  }

  function handleDragStart(event: DragStartEvent) {
    setActiveIssueId(String(event.active.id))
    updatePreview({base: board, board})
  }

  // Moves the card into a column as soon as the drag crosses into it, so that
  // column opens a gap and animates its cards aside. dnd-kit only displaces
  // items in a SortableContext that already holds the dragged one, so without
  // this the target column sat still until the drop. Moves within a column need
  // no state: the sortable strategy previews those itself.
  function handleDragOver({active, over}: DragOverEvent) {
    // Rebuilt from the prop if the board was refetched mid-drag.
    const current =
      previewRef.current?.base === board ? previewRef.current : {base: board, board}
    const target = dropTarget(active, over)
    const issueId = String(active.id)
    if (!target || issueColumnId(current.board, issueId) === target.workflowStateId) {
      return
    }
    updatePreview({...current, board: moveIssueOver(current.board, issueId, target)})
  }

  function handleDragCancel() {
    setActiveIssueId(null)
    updatePreview(null)
  }

  async function handleDragEnd({active, over}: DragEndEvent) {
    setActiveIssueId(null)
    const issueId = String(active.id)
    const target = dropTarget(active, over)
    const current = previewRef.current
    const arranged = current?.base === board ? current.board : board
    const shown = target ? moveIssueOver(arranged, issueId, target) : arranged
    const optimisticBoard =
      target && !isReordering ? buildOptimisticBoard(completeBoard, shown, issueId) : null
    const workflowStateId = issueColumnId(shown, issueId)
    const neighbors =
      optimisticBoard && workflowStateId
        ? boardIssueNeighbors(optimisticBoard, workflowStateId, issueId)
        : null
    if (!optimisticBoard || !workflowStateId || !neighbors) {
      updatePreview(null)
      return
    }

    // Keep showing the drop until the board prop catches up with it. The
    // mutation writes the optimistic board in its async onMutate, and React
    // Query hands that to React on a later task; letting go of the preview now
    // would paint the card back in its old column for a few frames first.
    updatePreview({base: board, board: shown})
    try {
      await onReorder({
        issueId,
        workflowStateId,
        ...neighbors,
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
      data-dragging={activeIssueId !== null}
    >
      {reorderError ? <ErrorState error={reorderError}/> : null}
      {isReordering ? (
        <p className="kanban-save-state" role="status">
          <Loader2 className="auth-spin" aria-hidden="true"/>
          Saving board
        </p>
      ) : null}
      <DndContext
        sensors={sensors}
        collisionDetection={boardCollisionDetection}
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragCancel={handleDragCancel}
        onDragEnd={handleDragEnd}
      >
        <div className="kanban-board-scroll">
          <div className="kanban-board">
            {shownBoard.columns.map((column) => (
              <KanbanColumn
                column={column}
                defaultAssigneeUserId={defaultAssigneeUserId}
                emptyLabel={emptyColumnLabel}
                isReordering={isReordering}
                isComposerOpen={composerWorkflowStateId === column.workflowState.id}
                isDropTarget={
                  dropColumnId === column.workflowState.id && dropColumnId !== originColumnId
                }
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
        <DragOverlay className="kanban-drag-overlay" dropAnimation={dropAnimation}>
          {activeIssue ? (
            <article className="kanban-card kanban-card-overlay">
              <KanbanIssueCardContent issue={activeIssue}/>
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
                        isComposerOpen,
                        isDropTarget,
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
  isComposerOpen: boolean
  isDropTarget: boolean
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
  const {setNodeRef} = useDroppable({
    id: kanbanColumnId(column.workflowState.id),
    data: {
      type: 'column',
      workflowStateId: column.workflowState.id,
    } satisfies KanbanDragData,
    disabled: isReordering,
  })

  return (
    <section
      className="kanban-column"
      data-category={column.workflowState.category}
      aria-label={`${column.workflowState.name} issues`}
    >
      <header className="kanban-column-header">
        <span className="kanban-column-title">
          <StatusIcon status={column.workflowState.category}/>
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
          <Plus aria-hidden="true"/>
        </Button>
      </header>
      <div className="kanban-column-body" data-over={isDropTarget} ref={setNodeRef}>
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
              <Loader2 className="auth-spin" aria-hidden="true"/>
            ) : null}
            {columnPagesQuery.isFetchingNextPage ? 'Loading' : 'Load more'}
          </Button>
        ) : null}
        {columnPagesQuery.error ? <ErrorState error={columnPagesQuery.error}/> : null}
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
    formState: {errors},
  } = useForm<QuickCreateIssueFormValues>({
    resolver: zodResolver(quickCreateIssueFormSchema),
    defaultValues: {title: ''},
  })
  const title = useWatch({control, name: 'title'}) ?? ''

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

      reset({title: ''})
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
          <Maximize2 aria-hidden="true"/>
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
            <X aria-hidden="true"/>
          </Button>
          <Button
            type="submit"
            size="icon-xs"
            disabled={!title.trim() || isSubmitting}
            aria-label="Create issue"
            title="Create issue"
          >
            {isSubmitting ? (
              <Loader2 aria-hidden="true" className="auth-spin"/>
            ) : (
              <Plus aria-hidden="true"/>
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
                             onIssueSelect,
                           }: {
  issue: IssueSummary
  workflowStateId: string
  isActive: boolean
  isReordering: boolean
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
    disabled: isReordering,
  })

  return (
    <article
      className="kanban-card"
      data-active={isActive}
      data-dragging={isDragging}
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
      }}
    >
      <button
        className="kanban-drag-handle"
        type="button"
        disabled={isReordering}
        aria-label={`Move ${issue.title}`}
        title="Move issue"
        {...attributes}
        {...listeners}
      >
        <GripVertical aria-hidden="true"/>
      </button>
      <button
        className="kanban-card-open"
        type="button"
        onClick={() => onIssueSelect(issue.id)}
      >
        <KanbanIssueCardContent issue={issue}/>
      </button>
    </article>
  )
}

function KanbanIssueCardContent({issue}: { issue: IssueSummary }) {
  return (
    <span className="kanban-card-content">
      <strong>{issue.title}</strong>
      {issue.labels.length > 0 ? (
        <span className="issue-label-row">
          {issue.labels.map((label) => (
            <LabelBadge label={label} key={label.id}/>
          ))}
        </span>
      ) : null}
      <span className="kanban-card-meta">
        <PriorityBadge priority={issue.priority}/>
        <span title={issue.assignee?.email ?? 'Unassigned'}>
          <UserCircle aria-hidden="true"/>
          {issue.assignee ? issue.assignee.displayName || issue.assignee.email : 'Unassigned'}
        </span>
        {issue.dueDate ? (
          <span>
            <CalendarDays aria-hidden="true"/>
            {formatDateOnly(issue.dueDate)}
          </span>
        ) : null}
        {issue.watched ? (
          <span>
            <Star aria-hidden="true" fill="currentColor"/>
          </span>
        ) : null}
        <span>
          <MessageSquare aria-hidden="true"/>
          {issue.commentCount ?? 0}
        </span>
      </span>
    </span>
  )
}
