// Runs the hidden checks against the user's workspace and reports each result on its own marked line.
// Owned by Engiens. The marker is read from a file that is deleted before any user code is loaded.
import { spawnSync } from 'node:child_process'
import { readdirSync, readFileSync, unlinkSync, writeSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const CHECK_TIMEOUT_MS = 5000
const here = fileURLToPath(new URL('.', import.meta.url))
const OWN = /\/(engiens\.mjs|engiens_run\.mjs|engiens_checks\.(mjs|ts))/
const COMPILE_CODES = new Set(['ERR_INVALID_TYPESCRIPT_SYNTAX', 'ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX'])

const noncePath = here + '.engiens_nonce'
const marker = '@@ENGIENS:' + readFileSync(noncePath, 'utf8').trim() + ':'
unlinkSync(noncePath)
const emit = (data) => writeSync(1, '\n' + marker + JSON.stringify(data) + '\n')

class CheckTimeout extends Error {}

/** Type, message and the last frame in the user's own files. Check files are never shown. */
function describe(error) {
  if (!(error instanceof Error)) return `Thrown value: ${String(error)}`.slice(0, 1000)
  let text = `${error.name}: ${error.message}`
  const prefix = 'file://' + here
  for (const line of (error.stack ?? '').split('\n')) {
    const i = line.indexOf(prefix)
    if (i < 0 || OWN.test(line)) continue
    const at = line.slice(i + prefix.length).match(/^([^:)]+):(\d+)/)
    if (at) {
      text += ` (at ${at[1]} line ${at[2]})`
      break
    }
  }
  return text.slice(0, 1000)
}

/** Every user source file, so syntax can be checked before anything runs. */
function userFiles(dir, rel = '') {
  return readdirSync(dir + rel, { withFileTypes: true }).flatMap((entry) => {
    const path = rel + entry.name
    if (entry.isDirectory()) return userFiles(dir, path + '/')
    return /\.(m?js|cjs)$/.test(entry.name) && !entry.name.startsWith('engiens') ? [path] : []
  }).sort()
}

// 1. Syntax: `node --check` each JavaScript file (never runs it) and report the first error with its file and line.
// TypeScript can't be checked this way (--check doesn't strip types); its syntax errors surface on import below.
for (const file of userFiles(here)) {
  const result = spawnSync(process.execPath, ['--disable-warning=ExperimentalWarning', '--check', file], { cwd: here, encoding: 'utf8' })
  if (result.status !== 0) {
    const lines = (result.stderr || '').split('\n')
    const at = (lines[0] || '').match(/:(\d+)$/)
    const error = lines.find((l) => /^\w*Error/.test(l)) || 'SyntaxError'
    emit({ kind: 'summary', outcome: 'compile_error', message: `${file}${at ? ' line ' + at[1] : ''}: ${error}`.slice(0, 1000) })
    process.exit(0)
  }
}

// 2. Load the checks (which import the user's modules), then 3. run each check with its own time limit.
const checksFile = process.argv[2]
let api
try {
  api = await import('./engiens.mjs')
  await import('./' + checksFile)
} catch (e) {
  const compile = e instanceof SyntaxError || COMPILE_CODES.has(e?.code)
  // TypeScript syntax errors start their stack with "/work/orders.ts:12".
  const at = COMPILE_CODES.has(e?.code) && String(e.stack).split('\n')[0].match(/^\/work\/(.+):(\d+)$/)
  const message = at ? `${at[1]} line ${at[2]}: SyntaxError: ${e.message}` : describe(e)
  emit({ kind: 'summary', outcome: compile ? 'compile_error' : 'load_error', message: message.slice(0, 1000) })
  process.exit(0)
}

for (const { name, fn } of api.checks) {
  const start = performance.now()
  let passed = false
  let message = null
  let timer
  try {
    await Promise.race([
      Promise.resolve().then(fn),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new CheckTimeout()), CHECK_TIMEOUT_MS)
      }),
    ])
    passed = true
  } catch (e) {
    if (e instanceof CheckTimeout) message = `Took longer than ${CHECK_TIMEOUT_MS / 1000} s`
    else if (e?.name === 'CheckFailure' || e?.name === 'AssertionError') message = String(e.message || 'A check assertion failed').slice(0, 1000)
    else message = describe(e)
  } finally {
    clearTimeout(timer)
  }
  emit({ kind: 'check', name, passed, message, ms: Math.round(performance.now() - start) })
}
emit({ kind: 'summary', outcome: 'ran' })
process.exit(0) // don't wait for timers or sockets user code left open
