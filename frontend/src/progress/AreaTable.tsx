import type { ProgressArea, ProgressEvidence } from '../api'
import { AssessmentMark } from '../review/AssessmentMark'
import { EvidenceList } from './EvidenceList'
import { INDICATOR_LABELS } from './labels'

/** The 16 rubric areas: the latest code assessment, the latest Scenario Lab answer, and the indicator, with evidence. */
export function AreaTable({ areas }: { areas: ProgressArea[] }) {
  const assessed = areas.filter((a) => a.indicator !== 'NOT_ASSESSED')
  const notAssessed = areas.filter((a) => a.indicator === 'NOT_ASSESSED')
  return (
    <div>
      <div className="hidden grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)] gap-4 border-b border-line pb-2 text-xs text-muted md:grid">
        <span>Area</span>
        <span>Latest review (code)</span>
        <span>Latest Scenario Lab answer</span>
        <span>Indicator</span>
      </div>
      <ul className="divide-y divide-line">
        {assessed.map((a) => (
          <li key={a.area} className="py-4">
            <div className="grid gap-x-4 gap-y-1.5 md:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)] md:items-center">
              <span className="text-[15px] text-ink">{a.name}</span>
              <Latest label="Latest review" item={latest(a.evidence, 'REVIEW')} />
              <Latest label="Latest Scenario Lab answer" item={latest(a.evidence, 'SCENARIO')} />
              <span className="text-sm text-ink/90">{INDICATOR_LABELS[a.indicator]}</span>
            </div>
            <p className="mt-1.5 text-xs leading-relaxed text-muted">{a.reason}</p>
            <EvidenceList evidence={a.evidence} />
          </li>
        ))}
      </ul>
      {notAssessed.length > 0 && (
        <p className="mt-4 text-xs leading-relaxed text-muted">
          Not assessed yet: {notAssessed.map((a) => a.name).join(', ')}.
        </p>
      )}
    </div>
  )
}

function Latest({ label, item }: { label: string; item: ProgressEvidence | undefined }) {
  return (
    <span className="flex items-center gap-2 text-sm">
      <span className="text-xs text-muted md:hidden">{label}:</span>
      {item ? <AssessmentMark value={item.level} /> : <span className="text-muted">—</span>}
    </span>
  )
}

/** Evidence is newest first; the latest counted one is what the column shows. */
function latest(evidence: ProgressEvidence[], source: ProgressEvidence['source']) {
  return evidence.find((e) => e.source === source && e.counted) ?? evidence.find((e) => e.source === source)
}
