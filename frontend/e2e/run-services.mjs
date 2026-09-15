import { existsSync, mkdtempSync, unlinkSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { spawn, spawnSync } from 'node:child_process'

const frontendDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repositoryDirectory = resolve(frontendDirectory, '..')
const backendDirectory = join(repositoryDirectory, 'backend')
const activityWatchPort =
  process.env.LIFE_TIMELINE_E2E_ACTIVITYWATCH_PORT ?? '5600'
const frontendPort = process.env.LIFE_TIMELINE_E2E_FRONTEND_PORT ?? '5173'
const backendPort = process.env.LIFE_TIMELINE_E2E_BACKEND_PORT ?? '8000'
const configuredDataDirectory = process.env.LIFE_TIMELINE_E2E_DATA_DIR
const stopFile = process.env.LIFE_TIMELINE_E2E_STOP_FILE
const ownsDataDirectory =
  process.env.LIFE_TIMELINE_E2E_OWNS_DATA_DIR === 'true' ||
  configuredDataDirectory === undefined
const dataDirectory = configuredDataDirectory
  ? resolve(configuredDataDirectory)
  : mkdtempSync(join(tmpdir(), 'life-timeline-e2e-'))

if (ownsDataDirectory) {
  process.env.LIFE_TIMELINE_E2E_DATA_DIR = dataDirectory
}

function pythonExecutable() {
  if (process.env.E2E_PYTHON) return process.env.E2E_PYTHON
  const virtualEnvironmentExecutable =
    process.platform === 'win32'
      ? join(backendDirectory, '.venv', 'Scripts', 'python.exe')
      : join(backendDirectory, '.venv', 'bin', 'python')
  if (existsSync(virtualEnvironmentExecutable))
    return virtualEnvironmentExecutable
  return process.platform === 'win32' ? 'python' : 'python3'
}

function startProcess(command, args, cwd, extraEnvironment = {}) {
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, ...extraEnvironment },
    stdio: 'ignore',
    shell: false,
    windowsHide: true,
  })
  return child
}

function stopProcess(child) {
  if (!child || child.killed) return
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], {
      stdio: 'ignore',
      windowsHide: true,
    })
  } else {
    child.kill('SIGTERM')
  }
}

async function waitForUrl(url, child, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`Service exited before becoming ready: ${url}`)
    }
    try {
      const response = await fetch(url)
      if (response.ok) return
    } catch {
      // The service is still starting.
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250))
  }
  throw new Error(`Timed out waiting for ${url}`)
}

let backend
let frontend
let fakeActivityWatch

process.once('exit', () => {
  stopProcess(frontend)
  stopProcess(backend)
  stopProcess(fakeActivityWatch)
  if (stopFile && existsSync(stopFile)) unlinkSync(stopFile)
})

fakeActivityWatch = startProcess(
  pythonExecutable(),
  [
    'scripts/fake_activitywatch_server.py',
    '--host',
    '127.0.0.1',
    '--port',
    activityWatchPort,
  ],
  backendDirectory,
  { PYTHONUNBUFFERED: '1' },
)

try {
  await waitForUrl(
    `http://127.0.0.1:${activityWatchPort}/health`,
    fakeActivityWatch,
  )
  backend = startProcess(
    pythonExecutable(),
    [
      'scripts/e2e_server.py',
      '--data-dir',
      dataDirectory,
      '--port',
      backendPort,
    ],
    backendDirectory,
    {
      LIFE_TIMELINE_DATA_DIR: dataDirectory,
      LIFE_TIMELINE_ACTIVITYWATCH_ENABLED: 'true',
      LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE: 'web',
      PYTHONUNBUFFERED: '1',
    },
  )
  await waitForUrl(`http://127.0.0.1:${backendPort}/api/v1/health`, backend)
  frontend = startProcess(
    process.execPath,
    [
      join(frontendDirectory, 'node_modules', 'vite', 'bin', 'vite.js'),
      '--host',
      '127.0.0.1',
      '--port',
      frontendPort,
    ],
    frontendDirectory,
  )

  let stopping = false
  const parentProcessId = process.ppid
  let parentWatch
  const stop = (exitCode = 0) => {
    if (stopping) return
    stopping = true
    if (parentWatch !== undefined) clearInterval(parentWatch)
    stopProcess(frontend)
    stopProcess(backend)
    stopProcess(fakeActivityWatch)
    setTimeout(() => process.exit(exitCode), 250)
  }

  if (parentProcessId > 1) {
    parentWatch = setInterval(() => {
      if (stopFile && existsSync(stopFile)) {
        stop()
        return
      }
      try {
        process.kill(parentProcessId, 0)
      } catch {
        stop()
      }
    }, 250)
    parentWatch.unref()
  }

  process.on('SIGINT', () => stop())
  process.on('SIGTERM', () => stop())
  backend.on('exit', (code) => {
    if (!stopping) stop(code ?? 1)
  })
  frontend.on('exit', (code) => {
    if (!stopping) stop(code ?? 1)
  })

  await new Promise(() => {})
} catch (error) {
  stopProcess(fakeActivityWatch)
  stopProcess(backend)
  console.error(error)
  process.exitCode = 1
}
