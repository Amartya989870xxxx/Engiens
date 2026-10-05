import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getLabHistory } from '../api'
import { rolesLabel, SENIORITY_LABELS } from '../scenario-lab/options'

const dateFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'long' })

/**
 * The end of a review: test yourself on this project, and every completed lab for this repository as
 * permanent, read-only history. Compact by design; each entry opens its own assessment page, never the lab.
 */
export function ScenarioLabHistory({ repositoryId, reviewId }: { repositoryId: string; reviewId: string }) {
  const history = useQuery({ queryKey: ['lab-history', repositoryId], queryFn: () => getLabHistory(repositoryId) })
  const labs = Array.isArray(history.data) ? history.data : []

  return (
    <section aria-labelledby="scenario-lab-history" className="space-y-12">
      <div className="rounded-xl border border-line bg-surface p-6 sm:p-8">
        <p className="text-xs uppercase tracking-[0.08em] text-muted">Scenario Lab</p>
        <h2 className="mt-2 font-display text-3xl text-ink">Test yourself on this project</h2>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-muted">
          Solve realistic production problems written from this repository’s own code, at the level you choose. Uses this
          review’s snapshot and findings.
        </p>
        <Link
          to={`/scenario-lab?review=${encodeURIComponent(reviewId)}`}
          className="mt-6 inline-flex h-9 items-center rounded-md bg-white px-4 text-sm font-medium text-black transition-colors hover:bg-white/85"
        >
          Test yourself on this project
        </Link>
      </div>

      <div>
        <h2 id="scenario-lab-history" className="mb-4 border-t border-line pt-6 font-display text-2xl text-ink">
          Scenario Lab history
        </h2>
        {history.isPending && <p className="text-sm text-muted">Loading…</p>}
        {!history.isPending && labs.length === 0 && (
          <p className="text-sm text-muted">No Scenario Lab attempts for this repository yet.</p>
        )}
        {labs.length > 0 && (
          <ul className="divide-y divide-line border-y border-line">
            {labs.map((l) => (
              <li key={l.id}>
                <Link
                  to={`/repositories/${repositoryId}/scenario-labs/${l.id}`}
                  className="group grid gap-1 py-4 sm:grid-cols-[5rem_1fr_auto] sm:items-baseline sm:gap-6"
                >
                  <span className="font-mono text-[13px] text-muted">Lab #{l.number}</span>
                  <span>
                    <span className="text-ink">
                      {rolesLabel(l.roles)} · {SENIORITY_LABELS[l.seniority]}
                    </span>
                    <span className="mt-0.5 block text-[13px] text-muted">
                      {l.scenariosCompleted} of {l.scenariosGenerated ?? l.scenarioCount} scenarios
                      {l.scenariosGenerated != null && l.scenariosGenerated < l.scenarioCount && ` (${l.scenariosGenerated} of ${l.scenarioCount} generated)`} ·{' '}
                      {dateFormat.format(new Date(l.completedAt))} · commit{' '}
                      <span className="font-mono">{l.commitSha.slice(0, 7)}</span>
                    </span>
                  </span>
                  <span className="text-sm text-muted transition-colors group-hover:text-ink">View assessment →</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
