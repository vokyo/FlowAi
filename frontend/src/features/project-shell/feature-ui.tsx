import { useEffect, type ReactNode } from 'react'
import { Building2, CheckCircle2, Circle, CircleDot, Flag, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { IssuePriority, IssueStatus, ProjectLabel } from '@/work/work-api'
import {
  formatPriority,
  getErrorMessage,
  getProjectMemberMutationErrorMessage,
} from './display-utils'

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
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !isCloseDisabled) onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [isCloseDisabled, onClose])

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !isCloseDisabled) onClose()
    }}>
      <section className="modal-panel" data-variant={variant} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <div><p className="breadcrumb-line">{eyebrow}</p><h2>{title}</h2></div>
          <Button type="button" variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close" disabled={isCloseDisabled}>
            <X aria-hidden="true" />
          </Button>
        </header>
        {children}
      </section>
    </div>
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
  return <span className="priority-badge"><Flag aria-hidden="true" />{formatPriority(priority)}</span>
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
