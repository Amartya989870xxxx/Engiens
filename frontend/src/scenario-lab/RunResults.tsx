import { useState } from 'react'
import type { RunResult } from '../api'

const STATUS: Record<RunResult['status'], string> = {
  PASSED: 'All checks passed',
  FAILED: 'Some checks failed',
  COMPILE_ERROR: 'Your code doesn’t compile',
  RUNTIME_ERROR: 'Your code stopped with an error',
  TIMEOUT: 'The run took too long',
  LIMIT_EXCEEDED: 'The run used too much memory',
}

/**
 * Objective execution feedback: which named checks passed. Never a percentage or a grade; the final
 * evaluation happens on Submit. Not animated: these are results, not prose.
 */
export function RunResults({ result }: { result: RunResult }) {
  const [showOutput, setShowOutput] = useState(false)
  const hasOutput = Boolean(result.stdout || result.stderr)
  const ran = result.total > 0
  return (
    <div className="space-y-3" aria-live="polite">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm text-ink">
          {ran ? (
            <>
              <span className="font-mono">
                {result.passed} / {result.total}
              </span>{' '}
              checks passing
            </>
          ) : (
            STATUS[result.status]
          )}
        </p>
        <p className="font-mono text-[11px] text-muted">
          {ran && result.status !== 'PASSED' && result.status !== 'FAILED' ? `${STATUS[result.status]} · ` : ''}
          {(result.durationMs / 1000).toFixed(1)} s
        </p>
      </div>
      {result.message && (
        <pre className="overflow-auto whitespace-pre-wrap rounded-md border border-line bg-canvas p-3 font-mono text-[12px] leading-relaxed text-ink/85">
          {result.message}
        </pre>
      )}
      {result.checks.length > 0 && (
        <ul className="divide-y divide-line border-y border-line" aria-label="Checks">
          {result.checks.map((c) => (
            <li key={c.name} className="flex gap-3 py-2 text-sm">
              <span aria-hidden className={`w-4 shrink-0 font-mono ${c.passed ? 'text-ink' : 'text-danger'}`}>
                {c.passed ? '✓' : '✗'}
              </span>
              <span className="min-w-0">
                <span className={c.passed ? 'text-ink' : 'text-ink'}>
                  <span className="sr-only">{c.passed ? 'Passed: ' : 'Failed: '}</span>
                  {c.name}
                </span>
                {c.message && <span className="mt-0.5 block break-words text-[13px] text-muted">{c.message}</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {hasOutput && (
        <div>
          <button type="button" onClick={() => setShowOutput((s) => !s)} className="text-xs text-muted hover:text-ink" aria-expanded={showOutput}>
            {showOutput ? 'Hide output' : 'Show output'}
          </button>
          {showOutput && (
            <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-md border border-line bg-canvas p-3 font-mono text-[12px] leading-relaxed text-ink/80">
              {result.stdout}
              {result.stderr && `${result.stdout ? '\n' : ''}${result.stderr}`}
              {result.outputTruncated && '\n… output truncated'}
            </pre>
          )}
        </div>
      )}
    </div>
  )
}
