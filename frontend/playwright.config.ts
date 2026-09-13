import { defineConfig } from '@playwright/test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const configuredDataDirectory = process.env.LIFE_TIMELINE_E2E_DATA_DIR
const ownsDataDirectory = configuredDataDirectory === undefined
const dataDirectory = ownsDataDirectory
  ? mkdtempSync(join(tmpdir(), 'life-timeline-e2e-'))
  : resolve(configuredDataDirectory)

process.env.LIFE_TIMELINE_E2E_DATA_DIR = dataDirectory
process.env.LIFE_TIMELINE_E2E_OWNS_DATA_DIR = ownsDataDirectory
  ? 'true'
  : 'false'

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
    baseURL: 'http://127.0.0.1:5173',
    screenshot: 'off',
    trace: 'off',
    video: 'off',
  },
  webServer: {
    command: 'node e2e/run-services.mjs',
    gracefulShutdown: { signal: 'SIGKILL', timeout: 1_000 },
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
