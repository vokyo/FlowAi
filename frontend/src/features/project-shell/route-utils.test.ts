import { describe, expect, it } from 'vitest'
import {
  analyticsRangeFromSearchParams,
  analyticsSearchParams,
  boardIssueViewFromSearchParams,
  isProjectAnalyticsPath,
  issuePath,
  issueViewModeFromSearchParams,
  issueViewSearchParams,
  normalizeAppSearchParams,
  pathWithSearchParams,
  projectAnalyticsPath,
  projectPath,
  workViewSearchParams,
} from './route-utils'

describe('project route query conversion', () => {
  it('normalizes work and analytics parameters without mutating the source', () => {
    const source = new URLSearchParams('layout=grid&view=everyone&range=90&extra=kept')

    expect(normalizeAppSearchParams(source, false).toString()).toBe('extra=kept')
    expect(normalizeAppSearchParams(source, true).toString()).toBe('range=90&extra=kept')
    expect(source.toString()).toBe('layout=grid&view=everyone&range=90&extra=kept')
  })

  it('round-trips list and board views through stable query parameters', () => {
    const listMine = workViewSearchParams(new URLSearchParams('range=7'), 'LIST', 'MINE')
    expect(listMine.toString()).toBe('layout=list&view=mine')
    expect(issueViewModeFromSearchParams(listMine)).toBe('LIST')
    expect(boardIssueViewFromSearchParams(listMine)).toBe('MINE')

    const boardUnassigned = workViewSearchParams(listMine, 'BOARD', 'UNASSIGNED')
    expect(boardUnassigned.toString()).toBe('view=unassigned')
    expect(issueViewSearchParams(new URLSearchParams('view=mine&range=90')).toString()).toBe(
      'view=mine',
    )
  })

  it('normalizes analytics ranges and builds canonical paths', () => {
    expect(analyticsRangeFromSearchParams(new URLSearchParams('range=7'))).toBe(7)
    expect(analyticsRangeFromSearchParams(new URLSearchParams('range=bogus'))).toBe(30)
    expect(analyticsSearchParams(new URLSearchParams('layout=list'), 90).toString()).toBe(
      'layout=list&range=90',
    )
    expect(projectPath('workspace', 'project')).toBe(
      '/app/workspaces/workspace/projects/project',
    )
    expect(projectAnalyticsPath('workspace', 'project')).toBe(
      '/app/workspaces/workspace/projects/project/analytics',
    )
    expect(issuePath('workspace', 'project', 'issue')).toBe(
      '/app/workspaces/workspace/projects/project/issues/issue',
    )
    expect(pathWithSearchParams('/app', '?layout=list')).toBe('/app?layout=list')
    expect(isProjectAnalyticsPath('/app/workspaces/w/projects/p/analytics')).toBe(true)
  })
})
