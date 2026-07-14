import { describe, expect, it } from 'vitest'
import { buildIssueListFilterState } from './issue-filter-utils'

describe('issue list filter conversion', () => {
  it('normalizes active defaults to an empty API filter', () => {
    const state = buildIssueListFilterState({
      searchQuery: '  ', workflowFilter: 'ACTIVE', priorityFilter: '', labelFilter: '', assigneeFilter: '',
    })
    expect(state.filters).toEqual({
      status: undefined,
      workflowStateId: undefined,
      priority: undefined,
      labelId: undefined,
      assigneeUserId: undefined,
      q: undefined,
    })
    expect(state.hasFilters).toBe(false)
  })

  it('maps archived and workflow-state views without changing other filters', () => {
    expect(buildIssueListFilterState({
      searchQuery: ' release ', workflowFilter: 'ARCHIVED', priorityFilter: 'HIGH', labelFilter: 'label-1', assigneeFilter: 'user-1',
    }).filters).toEqual({
      status: 'ARCHIVED', workflowStateId: undefined, priority: 'HIGH', labelId: 'label-1', assigneeUserId: 'user-1', q: 'release',
    })
    expect(buildIssueListFilterState({
      searchQuery: '', workflowFilter: 'state-1', priorityFilter: '', labelFilter: '', assigneeFilter: '',
    }).filters.workflowStateId).toBe('state-1')
  })
})
