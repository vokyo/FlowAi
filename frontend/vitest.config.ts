import { existsSync, globSync } from 'node:fs'
import path from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// A test file whose subject does not exist is almost always a rename that only
// got half applied — the tests keep passing while pointing at something else.
// `work/pagination-api.test.ts` sat next to `work-api.ts` this way for months.
const testsWithoutSubject = globSync('src/**/*.test.{ts,tsx}', { cwd: __dirname })
  .filter((testPath) =>
    !['ts', 'tsx'].some((extension) =>
      existsSync(path.resolve(__dirname, testPath.replace(/\.test\.tsx?$/, `.${extension}`))),
    ),
  )

if (testsWithoutSubject.length > 0) {
  throw new Error(
    `Test files with no matching subject module:\n  ${testsWithoutSubject.join('\n  ')}`,
  )
}

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    clearMocks: true,
  },
})
