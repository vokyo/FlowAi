import { useEffect, useState, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'

/**
 * Shown while a run or a revision is under way. The request waits for the whole
 * run, which takes tens of seconds, so the elapsed time shows it is still going.
 */
export function AgentWorking({ children }: { children: ReactNode }) {
  const [seconds, setSeconds] = useState(0)

  useEffect(() => {
    const startedAt = Date.now()
    const timer = window.setInterval(() => {
      setSeconds(Math.floor((Date.now() - startedAt) / 1000))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [])

  return (
    <div className="agent-working" role="status">
      <Loader2 className="auth-spin" aria-hidden="true" />
      <span>{children}</span>
      <span className="agent-working-time">{seconds}s</span>
    </div>
  )
}
