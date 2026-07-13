import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

const frontendDirectory = path.dirname(fileURLToPath(import.meta.url))
const backendDirectory = path.resolve(frontendDirectory, '../backend')
const frontendUrl = 'http://127.0.0.1:4173'
const backendUrl = 'http://127.0.0.1:18080'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }]]
    : 'list',
  use: {
    baseURL: frontendUrl,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    ...devices['Desktop Chrome'],
  },
  webServer: [
    {
      command: './mvnw -Dspring-boot.run.main-class=com.vokyo.backend.TestBackendApplication -Dspring-boot.run.arguments=--server.port=18080 spring-boot:test-run',
      cwd: backendDirectory,
      env: {
        OPENAI_API_KEY: 'dummy',
      },
      reuseExistingServer: false,
      timeout: 120_000,
      url: `${backendUrl}/actuator/health`,
    },
    {
      command: 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
      cwd: frontendDirectory,
      env: {
        VITE_API_PROXY_TARGET: backendUrl,
      },
      reuseExistingServer: false,
      timeout: 60_000,
      url: frontendUrl,
    },
  ],
})
