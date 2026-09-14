import { rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, relative, resolve, sep } from 'node:path'

async function waitForBackendShutdown(): Promise<void> {
  const backendPort = process.env.LIFE_TIMELINE_E2E_BACKEND_PORT ?? '8000'
  const healthUrl = `http://127.0.0.1:${backendPort}/api/v1/health`
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    try {
      await fetch(healthUrl, { signal: AbortSignal.timeout(500) })
    } catch {
      return
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100))
  }
  throw new Error('Timed out waiting for the E2E backend to stop.')
}

export default async function globalTeardown() {
  if (process.env.LIFE_TIMELINE_E2E_CLEANUP_OWNER === 'run-services') {
    const stopFile = process.env.LIFE_TIMELINE_E2E_STOP_FILE
    if (stopFile) {
      writeFileSync(stopFile, 'stop')
      await waitForBackendShutdown()
    }
  }
  if (process.env.LIFE_TIMELINE_E2E_OWNS_DATA_DIR !== 'true') return

  const dataDirectory = process.env.LIFE_TIMELINE_E2E_DATA_DIR
  if (!dataDirectory) return

  const tempDirectory = resolve(tmpdir())
  const absoluteDataDirectory = resolve(dataDirectory)
  const pathRelativeToTemp = relative(tempDirectory, absoluteDataDirectory)
  const isInsideTemp =
    pathRelativeToTemp !== '' &&
    pathRelativeToTemp !== '..' &&
    !pathRelativeToTemp.startsWith(`..${sep}`) &&
    !tempDirectory.startsWith(`${absoluteDataDirectory}${sep}`)
  if (
    !isInsideTemp ||
    !basename(absoluteDataDirectory).startsWith('life-timeline-e2e-')
  ) {
    throw new Error('Refusing to remove an unmanaged E2E data directory.')
  }

  rmSync(absoluteDataDirectory, { recursive: true, force: true })
}
