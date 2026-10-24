import { afterEach, describe, expect, it } from 'vitest'
import { formatDate, formatDateOnly } from './display-utils'

const BrowserDateTimeFormat = Intl.DateTimeFormat

// What a browser set to Chinese does: a format given no locale comes out in Chinese.
function browserInChinese() {
  Intl.DateTimeFormat = function (locales?: string | string[], options?: Intl.DateTimeFormatOptions) {
    return new BrowserDateTimeFormat(locales ?? 'zh-CN', options)
  } as unknown as typeof Intl.DateTimeFormat
}

afterEach(() => {
  Intl.DateTimeFormat = BrowserDateTimeFormat
})

describe('dates in a browser set to another language', () => {
  it('shows a moment in English, like the rest of the interface', () => {
    browserInChinese()

    expect(formatDate('2026-10-24T09:30:00Z')).toMatch(/^Oct 24/)
  })

  it('shows a calendar date in English', () => {
    browserInChinese()

    expect(formatDateOnly('2026-10-24')).toBe('Oct 24, 2026')
  })
})
