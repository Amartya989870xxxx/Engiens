import { Link } from 'react-router-dom'
import type { ProgressEvidence } from '../api'
import { AssessmentMark } from '../review/AssessmentMark'
import { humanize } from '../scenario-lab/options'
import { formatDate, shortCommit } from './labels'

/**
 * The assessments behind an indicator, expandable in place: where each came from (review or Scenario Lab answer),
 * repository, commit, date, the assessment itself, and a link to the full record.
 */
export function EvidenceList({ evidence }: { evidence: ProgressEvidence[] }) {
  if (evidence.length === 0) return null
  return (
    <details className="group mt-2">
      <summary className="inline-flex cursor-pointer list-none items-center gap-1.5 text-xs text-muted transition-colors hover:text-ink [&::-webkit-details-marker]:hidden">
        <span aria-hidden className="inline-block transition-transform group-open:rotate-90">›</span>
        Evidence ({evidence.length})
      </summary>
      <ul className="mt-3 divide-y divide-line border-y border-line">
        {evidence.map((e, i) => (
          <li key={i} className={`grid gap-x-6 gap-y-1 py-3 text-[13px] sm:grid-cols-[9rem_1fr_auto] ${e.counted ? '' : 'opacity-60'}`}>
            <span className="text-muted">
              {e.source === 'REVIEW' ? 'Review' : 'Scenario Lab'}
              <span className="block text-xs">{formatDate(e.date)}</span>
            </span>
            <span className="min-w-0 text-ink/90">
              {e.source === 'SCENARIO' && e.scenarioTitle && <span className="block break-words text-ink">{e.scenarioTitle}</span>}
              <span className="text-muted">
                {e.repositoryName ?? 'Repository'} · <span className="font-mono text-xs">{shortCommit(e.commitSha)}</span>
                {e.category && <> · {humanize(e.category)}</>}
                {e.confidence === 'LOW' && <> · low confidence</>}
              </span>
              {e.note && <span className="mt-0.5 block text-xs text-muted">{e.note}</span>}
            </span>
            <span className="flex items-center gap-4 sm:justify-end">
              <AssessmentMark value={e.level} />
              <EvidenceLink evidence={e} />
            </span>
          </li>
        ))}
      </ul>
    </details>
  )
}

function EvidenceLink({ evidence: e }: { evidence: ProgressEvidence }) {
  const to = e.source === 'REVIEW' && e.reviewId ? `/reviews/${e.reviewId}` : e.labId ? `/repositories/${e.repositoryId}/scenario-labs/${e.labId}` : null
  if (!to) return null
  return (
    <Link to={to} className="text-xs text-muted underline underline-offset-4 hover:text-ink">
      {e.source === 'REVIEW' ? 'Open review' : 'Open lab'}
    </Link>
  )
}
