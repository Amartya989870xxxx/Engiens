import { Link } from 'react-router-dom'
import type { Assessment, Progress, StoredTeaching } from '../api'
import { ASSESSMENT } from '../review/assessment'
import { humanize, ROLE_LABELS, SENIORITY_LABELS } from '../scenario-lab/options'
import { formatDate } from './labels'

const VERDICT_ORDER: Assessment[] = ['STRONG', 'SOLID', 'DEVELOPING', 'NEEDS_ATTENTION', 'NOT_ASSESSABLE']

/** What has been practised in Scenario Lab, and which weak review areas haven't been practised yet. */
export function Practice({ progress: p, areaName }: { progress: Progress; areaName: (id: string) => string }) {
  const weak = p.practice.weakButUnpractised.map(areaName)
  if (p.evidence.labs === 0) {
    return (
      <div className="max-w-2xl text-sm leading-relaxed text-ink/90">
        <p>No completed Scenario Lab yet. Reviews assess the code in a repository; Scenario Lab assesses your own answers to production problems from it.</p>
        {weak.length > 0 && <p className="mt-3 text-ink/80">Worth practising first: {weak.join(', ')}.</p>}
        <Link to="/scenario-lab" className="mt-4 inline-block text-sm text-ink underline underline-offset-4">
          Open Scenario Lab
        </Link>
      </div>
    )
  }
  return (
    <div className="space-y-8">
      <ul className="divide-y divide-line border-y border-line">
        {p.practice.categories.map((c) => (
          <li key={c.category} className="grid gap-x-6 gap-y-1 py-3 text-sm sm:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1.4fr)]">
            <span className="text-ink">{humanize(c.category)}</span>
            <span className="text-muted">Counts under {areaName(c.area)}</span>
            <span className="text-ink/80">
              {c.answers} {c.answers === 1 ? 'answer' : 'answers'}:{' '}
              {VERDICT_ORDER.filter((v) => c.verdicts[v])
                .map((v) => `${c.verdicts[v]} ${ASSESSMENT[v].label}`)
                .join(', ')}
            </span>
          </li>
        ))}
      </ul>
      <p className="text-sm text-ink/80">
        <span className="text-muted">Roles practised: </span>
        {p.practice.roles.map((r) => `${ROLE_LABELS[r.value]} (${r.count})`).join(', ')}
        <span className="text-muted"> · Seniority: </span>
        {p.practice.seniorities.map((s) => `${SENIORITY_LABELS[s.value]} (${s.count})`).join(', ')}
      </p>
      {weak.length > 0 && (
        <p className="text-sm text-ink/80">
          <span className="text-muted">Weak in your latest review, not practised yet: </span>
          {weak.join(', ')}
        </p>
      )}
    </div>
  )
}

/** Where to go next: the deterministic list, then the stored teaching from the latest review and lab, quoted. */
export function NextAreas({ progress: p }: { progress: Progress }) {
  const { fromReview, fromLab } = p.recommendations
  const practiceLink = fromReview?.reviewId ? `/scenario-lab?review=${fromReview.reviewId}` : '/scenario-lab'
  return (
    <div className="space-y-10">
      {p.nextAreas.length === 0 ? (
        <p className="max-w-2xl text-sm text-ink/90">No area stands out as weak in the evidence so far.</p>
      ) : (
        <ol className="space-y-4">
          {p.nextAreas.map((n, i) => (
            <li key={n.area} className="flex gap-4">
              <span className="font-mono text-xs text-muted">{String(i + 1).padStart(2, '0')}</span>
              <span>
                <span className="text-[15px] text-ink">{n.name}</span>
                <span className="mt-1 block text-sm leading-relaxed text-ink/80">{n.why}</span>
              </span>
            </li>
          ))}
        </ol>
      )}
      <Link to={practiceLink} className="inline-block text-sm text-ink underline underline-offset-4">
        Practise in Scenario Lab
      </Link>
      {(fromReview || fromLab) && (
        <div className="grid gap-10 md:grid-cols-2">
          {fromReview && <Teaching title="From your latest review" teaching={fromReview} link={`/reviews/${fromReview.reviewId}`} />}
          {fromLab && (
            <Teaching title="From your latest lab" teaching={fromLab} link={`/repositories/${fromLab.repositoryId}/scenario-labs/${fromLab.labId}`} />
          )}
        </div>
      )}
    </div>
  )
}

function Teaching({ title, teaching: t, link }: { title: string; teaching: StoredTeaching; link: string }) {
  return (
    <section>
      <h3 className="text-xs uppercase tracking-[0.08em] text-muted">{title}</h3>
      <p className="mt-1 text-xs text-muted">
        {t.repositoryName} · {formatDate(t.date)} ·{' '}
        <Link to={link} className="underline underline-offset-4 hover:text-ink">
          open
        </Link>
      </p>
      <ul className="mt-3 space-y-3">
        {t.topics.map((topic) => (
          <li key={topic.topic} className="text-sm">
            <span className="text-ink">{topic.topic}</span>
            <span className="mt-0.5 block leading-relaxed text-ink/75">{topic.why}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
