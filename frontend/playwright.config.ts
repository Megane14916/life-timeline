import { defineConfig } from '@playwright/test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const configuredDataDirectory = process.env.LIFE_TIMELINE_E2E_DATA_DIR
const ownsDataDirectory = configuredDataDirectory === undefined
const dataDirectory = ownsDataDirectory
  ? mkdtempSync(join(tmpdir(), 'life-timeline-e2e-'))
  : resolve(configuredDataDirectory)
const frontendPort = process.env.LIFE_TIMELINE_E2E_FRONTEND_PORT ?? '5173'
const backendPort = process.env.LIFE_TIMELINE_E2E_BACKEND_PORT ?? '8000'
const baseURL = `http://127.0.0.1:${frontendPort}`
const stopFile = join(
  tmpdir(),
  `life-timeline-e2e-stop-${process.pid}-${Date.now()}`,
)

process.env.LIFE_TIMELINE_E2E_DATA_DIR = dataDirectory
process.env.LIFE_TIMELINE_E2E_OWNS_DATA_DIR = ownsDataDirectory
  ? 'true'
  : 'false'
process.env.LIFE_TIMELINE_E2E_CLEANUP_OWNER = 'run-services'
process.env.LIFE_TIMELINE_E2E_STOP_FILE = stopFile

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  globalTeardown: './e2e/global-teardown.ts',
  reporter: [
    ['list'],
    [
      'junit',
      {
        outputFile:
          process.env.E2E_JUNIT_RESULTS ?? 'test-results/e2e-junit.xml',
      },
    ],
  ],
  use: {
    baseURL,
    screenshot: 'off',
    trace: 'off',
    video: 'off',
  },
  webServer: {
    command: 'node e2e/run-services.mjs',
    gracefulShutdown: { signal: 'SIGINT', timeout: 5_000 },
    url: baseURL,
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
