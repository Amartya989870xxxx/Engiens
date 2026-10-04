import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError, getExcerpt, type ReviewEvidence } from '../api'

/** Citations under a finding. File lines can be expanded into the exact code the reviewer saw. */
export function EvidenceList({ reviewId, evidence }: { reviewId: string; evidence: ReviewEvidence[] }) {
  if (evidence.length === 0) return null
  return (
    <ul className="mt-3 space-y-1.5" aria-label="Evidence">
      {evidence.map((e, i) => (
        <li key={i}>
          {e.file ? <FileEvidence reviewId={reviewId} evidence={e} /> : <SignalEvidence id={e.signalId ?? ''} />}
        </li>
      ))}
    </ul>
  )
}

function SignalEvidence({ id }: { id: string }) {
  return (
    <span className="text-xs text-muted">
      Engiens check <span className="font-mono text-[12px] text-ink/80">{id}</span>
    </span>
  )
}

function FileEvidence({ reviewId, evidence }: { reviewId: string; evidence: ReviewEvidence }) {
  const [open, setOpen] = useState(false)
  const file = evidence.file!
  const start = evidence.lineStart ?? null
  const end = evidence.lineEnd ?? start
  const lines = start === null ? null : end && end !== start ? `Lines ${start}–${end}` : `Line ${start}`
  const excerpt = useQuery({
    queryKey: ['excerpt', reviewId, file, start, end],
    queryFn: () => getExcerpt(reviewId, file, start!, end!),
    enabled: open && start !== null,
    staleTime: Infinity,
    retry: false,
  })
  return (
    <div>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="break-all font-mono text-[12px] text-ink/90">{file}</span>
        {lines && (
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            aria-expanded={open}
            className="text-xs text-muted underline-offset-4 hover:text-ink hover:underline focus-visible:outline-2 focus-visible:outline-white"
          >
            {lines} {open ? '↑' : '↓'}
          </button>
        )}
      </div>
      {open && (
        <div className="mt-2">
          {excerpt.isPending && <p className="text-xs text-muted">Loading code…</p>}
          {excerpt.error && (
            <p className="text-xs text-muted">
              {excerpt.error instanceof ApiError ? excerpt.error.message : 'The code could not be loaded.'}
            </p>
          )}
          {excerpt.data && (
            <pre className="max-h-96 overflow-auto rounded-md border border-line bg-canvas py-2 text-[12px] leading-relaxed">
              <code>
                {excerpt.data.lines.map((l) => (
                  <span key={l.number} className="block px-3">
                    <span className="mr-4 inline-block w-8 select-none text-right text-muted/60">{l.number}</span>
                    {l.text}
                  </span>
                ))}
              </code>
            </pre>
          )}
        </div>
      )}
    </div>
  )
}
