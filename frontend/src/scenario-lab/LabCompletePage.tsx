import { useEffect } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getLab } from '../api'
import { ErrorBanner } from '../ui'
import { rememberLab } from './lastLab'

/** The way out of a finished lab. From here on Scenario Lab is empty again; the assessment is history. */
export function LabCompletePage() {
  const { labId = '' } = useParams()
  const lab = useQuery({ queryKey: ['scenario-lab', 'lab', labId], queryFn: () => getLab(labId), retry: false })

  useEffect(() => rememberLab(null), [])

  if (lab.isPending) return <p className="px-6 py-10 text-sm text-muted">Loading…</p>
  if (lab.error) return <div className="mx-auto max-w-2xl px-4 py-16"><ErrorBanner error={lab.error} /></div>
  if (lab.data.status !== 'COMPLETED') return <Navigate to="/scenario-lab" replace />

  const l = lab.data
  return (
    <div className="mx-auto max-w-2xl px-4 py-16 md:py-24">
      <p className="text-xs uppercase tracking-[0.08em] text-muted">{l.repositoryName}</p>
      <h1 className="mt-3 font-display text-5xl leading-tight text-ink">Scenario Lab complete.</h1>
      <p className="mt-5 text-[15px] leading-relaxed text-muted">
        Your assessment has been saved to this repository’s history. Scenario Lab is ready for a new lab whenever you are.
      </p>
      <div className="mt-10 flex flex-wrap gap-3">
        <Link
          to={`/repositories/${l.repositoryId}/scenario-labs/${l.id}`}
          className="inline-flex h-9 items-center rounded-md bg-white px-4 text-sm font-medium text-black hover:bg-white/85"
        >
          View assessment
        </Link>
        {l.reviewId && (
          <Link to={`/reviews/${l.reviewId}`} className="inline-flex h-9 items-center rounded-md border border-line px-4 text-sm text-ink hover:border-line-strong hover:bg-raised">
            Back to review
          </Link>
        )}
        <Link to="/dashboard" className="inline-flex h-9 items-center rounded-md px-4 text-sm text-muted hover:text-ink">
          Go to dashboard
        </Link>
      </div>
    </div>
  )
}
