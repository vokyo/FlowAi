import { useRef, type ReactNode } from 'react'
import { Building2, CheckCircle2, Circle, CircleDot, Flag, X } from 'lucide-react'
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

export function StatusIcon({ status }: { status: IssueStatus }) {
  if (status === 'DONE') return <CheckCircle2 aria-hidden="true" className="status-icon status-icon-done" />
  if (status === 'IN_PROGRESS') return <CircleDot aria-hidden="true" className="status-icon status-icon-progress" />
  if (status === 'ARCHIVED') return <Circle aria-hidden="true" className="status-icon status-icon-muted" />
  return <Circle aria-hidden="true" className="status-icon" />
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
  return <span className="label-badge"><span className="label-swatch" style={{ backgroundColor: label.color }} aria-hidden="true" />{label.name}</span>
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
