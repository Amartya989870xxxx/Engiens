import type { ProgressArea, ProgressIndicator } from '../api'
import { ASSESSMENT } from '../review/assessment'
import { EvidenceList } from './EvidenceList'
import { formatDate, shortCommit } from './labels'

const GROUPS: { indicator: ProgressIndicator; title: string; empty: string }[] = [
  { indicator: 'CONSISTENT_STRENGTH', title: 'Consistent strengths', empty: 'None identified yet.' },
  { indicator: 'RECURRING_GAP', title: 'Recurring gaps', empty: 'None identified yet.' },
  { indicator: 'IMPROVING', title: 'Improving', empty: 'Not enough evidence across assessments yet.' },
  { indicator: 'INCONSISTENT', title: 'Inconsistent', empty: 'None identified.' },
]

/**
 * The evidence-based indicators, each with its reason and the assessments behind it. Project-level changes (the
 * same repository rated differently at a later commit) are listed separately: they describe the code, not the person.
 */
export function Indicators({ areas }: { areas: ProgressArea[] }) {
  const patterns = areas.filter((a) => GROUPS.some((g) => g.indicator === a.indicator))
  const changes = areas.flatMap((a) => a.projectChanges.map((c) => ({ area: a, change: c })))
  const varied = areas.filter((a) => a.variedOnSameCode.length > 0)

  return (
    <div className="space-y-10">
      {patterns.length === 0 ? (
        <p className="max-w-2xl text-sm leading-relaxed text-ink/90">
          Not enough history to identify a trend yet. Indicators appear once an area has been assessed on at least two
          occasions: another review after you change the code, a review of another repository, or a completed Scenario Lab.
        </p>
      ) : (
        <div className="grid gap-x-10 gap-y-8 md:grid-cols-2">
          {GROUPS.map((g) => {
            const items = patterns.filter((a) => a.indicator === g.indicator)
            return (
              <section key={g.indicator} aria-labelledby={`indicator-${g.indicator}`}>
                <h3 id={`indicator-${g.indicator}`} className="text-xs uppercase tracking-[0.08em] text-muted">
                  {g.title}
                </h3>
                {items.length === 0 ? (
                  <p className="mt-3 text-sm text-muted">{g.empty}</p>
                ) : (
                  <ul className="mt-3 space-y-5">
                    {items.map((a) => (
                      <li key={a.area}>
                        <p className="text-[15px] text-ink">{a.name}</p>
                        <p className="mt-1 text-sm leading-relaxed text-ink/80">{a.reason}</p>
                        <EvidenceList evidence={a.evidence} />
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )
          })}
        </div>
      )}

      {changes.length > 0 && (
        <section aria-labelledby="project-changes">
          <h3 id="project-changes" className="text-xs uppercase tracking-[0.08em] text-muted">
            Project-level changes
          </h3>
          <p className="mt-2 max-w-2xl text-sm text-muted">
            The same repository rated differently at a later commit. This describes how the project's code changed, not a
            measure of you on its own.
          </p>
          <ul className="mt-4 space-y-4">
            {changes.map(({ area, change: c }) => (
              <li key={`${area.area}-${c.repositoryId}`} className="text-sm">
                <span className="text-ink">{area.name}</span>
                <span className="text-ink/80">
                  {' '}
                  in {c.repositoryName}: {ASSESSMENT[c.from].label} → {ASSESSMENT[c.to].label}
                </span>
                <span className="text-muted">
                  {' '}
                  (<span className="font-mono text-xs">{shortCommit(c.fromCommit)}</span>, {formatDate(c.fromDate)} →{' '}
                  <span className="font-mono text-xs">{shortCommit(c.toCommit)}</span>, {formatDate(c.toDate)})
                </span>
                <EvidenceList evidence={area.evidence.filter((e) => e.source === 'REVIEW' && e.repositoryId === c.repositoryId)} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {varied.length > 0 && (
        <p className="max-w-2xl text-xs leading-relaxed text-muted">
          Reviews of the same commit disagreed on {varied.map((a) => a.name).join(', ')} (in{' '}
          {[...new Set(varied.flatMap((a) => a.variedOnSameCode))].join(', ')}). Only the latest of those reviews is counted:
          the code didn't change, so the difference isn't progress.
        </p>
      )}
    </div>
  )
}
