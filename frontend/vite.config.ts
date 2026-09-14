import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const runtimeEnvironment = (
  globalThis as unknown as {
    process: { env: Record<string, string | undefined> }
  }
).process.env
const backendPort = runtimeEnvironment.LIFE_TIMELINE_E2E_BACKEND_PORT ?? '8000'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': `http://127.0.0.1:${backendPort}`,
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    reporters: [['default'], ['junit', { outputFile: 'vitest-junit.xml' }]],
    include: ['src/**/*.{test,spec}.?(c|m)[jt]s?(x)'],
    exclude: ['e2e/**', 'node_modules/**'],
    setupFiles: './src/test/setup.ts',
  },
})
