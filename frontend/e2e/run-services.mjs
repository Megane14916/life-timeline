import { closeSync, existsSync, mkdirSync, openSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn, spawnSync } from 'node:child_process'

const frontendDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repositoryDirectory = resolve(frontendDirectory, '..')
const backendDirectory = join(repositoryDirectory, 'backend')
const artifactDirectory = resolve(
  process.env.E2E_ARTIFACT_DIR ?? join(repositoryDirectory, 'e2e-artifacts'),
)
const dataDirectory = resolve(
  process.env.LIFE_TIMELINE_E2E_DATA_DIR ?? join(artifactDirectory, 'database'),
)

mkdirSync(artifactDirectory, { recursive: true })
mkdirSync(dataDirectory, { recursive: true })

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

function startProcess(command, args, cwd, logName, extraEnvironment = {}) {
  const logPath = join(artifactDirectory, logName)
  const logFileDescriptor = openSync(logPath, 'w')
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, ...extraEnvironment },
    stdio: ['ignore', logFileDescriptor, logFileDescriptor],
    shell: false,
    windowsHide: true,
  })
  child.logFileDescriptor = logFileDescriptor
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
  const logFileDescriptor = child.logFileDescriptor
  child.logFileDescriptor = undefined
  if (logFileDescriptor !== undefined) closeSync(logFileDescriptor)
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

process.once('exit', () => {
  stopProcess(frontend)
  stopProcess(backend)
})

backend = startProcess(
  pythonExecutable(),
  ['scripts/e2e_server.py', '--data-dir', dataDirectory],
  backendDirectory,
  'backend.log',
  {
    LIFE_TIMELINE_DATA_DIR: dataDirectory,
    PYTHONUNBUFFERED: '1',
  },
)

try {
  await waitForUrl('http://127.0.0.1:8000/api/v1/health', backend)
  frontend = startProcess(
    process.execPath,
    [
      join(frontendDirectory, 'node_modules', 'vite', 'bin', 'vite.js'),
      '--host',
      '127.0.0.1',
      '--port',
      '5173',
    ],
    frontendDirectory,
    'frontend.log',
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
    setTimeout(() => process.exit(exitCode), 250)
  }

  if (parentProcessId > 1) {
    parentWatch = setInterval(() => {
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
  stopProcess(backend)
  console.error(error)
  process.exitCode = 1
}
