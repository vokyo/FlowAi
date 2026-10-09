import type { ReactNode } from 'react'

/** The agent stopped without a plan and said what it could not find out. */
export function AgentMissingNotice({ missing, children }: { missing: string[]; children: ReactNode }) {
  return (
    <div className="agent-notice" data-tone="warning" role="status">
      <p>{children}</p>
      {missing.length > 0 ? (
        <ul>
          {missing.map((entry) => <li key={entry}>{entry}</li>)}
        </ul>
      ) : null}
    </div>
  )
}
