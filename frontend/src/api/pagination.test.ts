import { describe, expect, it } from 'vitest'
import { compareCreatedAtAscending, mergeCursorPageItems } from './pagination'

describe('cursor pagination helpers', () => {
  it('merges pages and de-duplicates overlapping records by id', () => {
    const items = mergeCursorPageItems([
      { items: [{ id: 'new', value: 1 }, { id: 'overlap', value: 1 }], nextCursor: 'next' },
      { items: [{ id: 'overlap', value: 2 }, { id: 'old', value: 3 }], nextCursor: null },
    ])

    expect(items).toEqual([
      { id: 'new', value: 1 },
      { id: 'overlap', value: 2 },
      { id: 'old', value: 3 },
    ])
  })

  it('sorts chronologically and uses the id as a stable tie breaker', () => {
    const items = [
      { id: 'b', createdAt: '2026-07-14T10:00:00Z' },
      { id: 'c', createdAt: '2026-07-14T11:00:00Z' },
      { id: 'a', createdAt: '2026-07-14T10:00:00Z' },
    ]

    expect(items.sort(compareCreatedAtAscending).map((item) => item.id)).toEqual(['a', 'b', 'c'])
  })
})
