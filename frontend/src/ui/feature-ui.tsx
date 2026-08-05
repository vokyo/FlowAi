import { useRef, type CSSProperties, type ReactNode } from 'react'
import { Building2, Flag, X } from 'lucide-react'
import { LABEL_COLORS } from '@/ui/label-colors'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog'
import type { IssuePriority, IssueStatus, ProjectLabel } from '@/api/work-api'
import {
  formatPriority,
  getErrorMessage,
  getProjectMemberMutationErrorMessage,
} from '@/ui/display-utils'

export function ModalShell({
  title, eyebrow, children, onClose, variant = 'default', isCloseDisabled = false,
}: {
  title: string
  eyebrow: string
  children: ReactNode
  onClose: () => void
  variant?: 'default' | 'issue' | 'members'
  isCloseDisabled?: boolean
}) {
  // Callers mount ModalShell conditionally rather than passing an open flag, so
  // the Dialog is always open and closing is delegated back through onClose.
  // That also means Radix never observes a closed->open transition and has no
  // trigger to hand focus back to, so the opener is captured here during the
  // first render — before the focus scope moves focus into the panel.
  const returnFocusRef = useRef<HTMLElement | null>(null)
  if (returnFocusRef.current === null) {
    returnFocusRef.current = document.activeElement as HTMLElement | null
  }

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent
        className="modal-panel"
        data-variant={variant}
        showCloseButton={false}
        aria-describedby={undefined}
        onEscapeKeyDown={(event) => { if (isCloseDisabled) event.preventDefault() }}
        onInteractOutside={(event) => { if (isCloseDisabled) event.preventDefault() }}
        onCloseAutoFocus={(event) => {
          const opener = returnFocusRef.current
          if (opener?.isConnected) {
            event.preventDefault()
            opener.focus()
          }
        }}
      >
        <header className="modal-header">
          <div>
            <p className="breadcrumb-line">{eyebrow}</p>
            <DialogTitle asChild><h2>{title}</h2></DialogTitle>
          </div>
          <Button type="button" variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close" disabled={isCloseDisabled}>
            <X aria-hidden="true" />
          </Button>
        </header>
        {children}
      </DialogContent>
    </Dialog>
  )
}

export function BreadcrumbLine({ items }: { items: string[] }) {
  return <p className="breadcrumb-line">{items.map((item, index) => (
    <span key={`${item}-${index}`}>{index > 0 ? <span aria-hidden="true">/</span> : null}{item}</span>
  ))}</p>
}

/**
 * One glyph family rather than three borrowed icons: a ring, plus a wedge whose
 * angle is how far the state has got. Todo/In progress/Done used to be Circle,
 * CircleDot and CheckCircle2 — three shapes from three metaphors, so the set
 * read as unrelated marks instead of a scale.
 *
 * Angles are fixed because the categories are: WorkflowStateCategory on the
 * backend is exactly TODO, IN_PROGRESS and DONE.
 */
export function StatusIcon({ status }: { status: IssueStatus }) {
  if (status === 'DONE') {
    return (
      <svg className="status-icon status-icon-done" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <circle cx="8" cy="8" r="7" fill="currentColor" />
        <path
          d="m5.2 8.1 1.9 1.9 3.7-4.2"
          stroke="var(--md-surface-container-lowest)"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    )
  }

  const className = status === 'IN_PROGRESS'
    ? 'status-icon status-icon-progress'
    : status === 'ARCHIVED'
      ? 'status-icon status-icon-muted'
      : 'status-icon'

  return (
    <svg className={className} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="8" cy="8" r="6.25" stroke="currentColor" strokeWidth="1.5" />
      {/* Half wedge: from 12 o'clock, sweeping clockwise to 6 o'clock. */}
      {status === 'IN_PROGRESS' ? (
        <path d="M8 8 L8 4.4 A3.6 3.6 0 0 1 8 11.6 Z" fill="currentColor" />
      ) : null}
    </svg>
  )
}

export function PriorityBadge({ priority }: { priority?: IssuePriority | null }) {
  if (!priority) return <span className="priority-badge priority-badge-empty">No priority</span>
  return (
    <span className="priority-badge" data-priority={priority}>
      <Flag aria-hidden="true" />{formatPriority(priority)}
    </span>
  )
}

export function LabelBadge({ label }: { label: ProjectLabel }) {
  // The colour goes in via a custom property rather than background-color so the
  // stylesheet can pull legacy values toward the palette's chroma — see
  // .label-swatch. Colours written before LABEL_COLORS existed are arbitrary.
  return <span className="label-badge"><span className="label-swatch" style={{ '--swatch': label.color } as CSSProperties} aria-hidden="true" />{label.name}</span>
}

/** Radiogroup of the eight palette swatches. Replaces a free colour picker. */
export function LabelColorPicker({ value, onChange, disabled = false, idPrefix }: {
  value: string
  onChange: (color: string) => void
  disabled?: boolean
  idPrefix: string
}) {
  return (
    <div className="label-color-picker" role="radiogroup" aria-label="Label color">
      {LABEL_COLORS.map((color) => (
        <button
          key={color.value}
          id={`${idPrefix}-${color.name}`}
          className="label-color-option"
          type="button"
          role="radio"
          aria-checked={value.toLowerCase() === color.value}
          aria-label={color.name}
          title={color.name}
          disabled={disabled}
          style={{ '--swatch': color.value } as CSSProperties}
          onClick={() => onChange(color.value)}
        />
      ))}
    </div>
  )
}

export function InlineState({ children }: { children: ReactNode }) {
  return <p className="app-state">{children}</p>
}

export function InlineNotice({ children, tone = 'default' }: { children: ReactNode; tone?: 'default' | 'warning' }) {
  return <p className="inline-notice" data-tone={tone}>{children}</p>
}

export function EmptyState({ title, body }: { title: string; body: string }) {
  return <div className="empty-state"><Building2 aria-hidden="true" /><strong>{title}</strong><p>{body}</p></div>
}

export function ErrorState({ error }: { error: Error }) {
  return <p className="app-error">{getErrorMessage(error)}</p>
}

export function ProjectMemberMutationErrorState({ error, action }: { error: Error; action: 'add' | 'update' | 'remove' }) {
  return <p className="app-error">{getProjectMemberMutationErrorMessage(error, action)}</p>
}
