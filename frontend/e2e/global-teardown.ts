import { rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, relative, resolve, sep } from 'node:path'

export default function globalTeardown() {
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
