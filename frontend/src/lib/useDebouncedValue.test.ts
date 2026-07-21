import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useDebouncedValue } from './useDebouncedValue'

describe('useDebouncedValue', () => {
  afterEach(() => vi.useRealTimers())

  it('publishes only the latest value after the delay', () => {
    vi.useFakeTimers()
    const { result, rerender } = renderHook(
      ({ value }) => useDebouncedValue(value, 300),
      { initialProps: { value: 'initial' } },
    )

    rerender({ value: 'first' })
    act(() => vi.advanceTimersByTime(299))
    expect(result.current).toBe('initial')

    rerender({ value: 'latest' })
    act(() => vi.advanceTimersByTime(300))
    expect(result.current).toBe('latest')
  })
})
