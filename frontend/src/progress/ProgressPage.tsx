import type { ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ApiError, getProgress, type Progress } from '../api'
import { inputClass, labelClass } from '../ui'
import { AreaTable } from './AreaTable'
import { Indicators } from './Indicators'
import { NextAreas, Practice } from './PracticeAndNext'
import { ProgressHistory } from './ProgressHistory'
import { formatDate } from './labels'

/**
 * How the developer's engineering is developing, from stored reviews and completed Scenario Labs. Every indicator is
 * calculated deterministically from that evidence and shows it; nothing here is a score or an AI judgement.
 */
export function ProgressPage() {
  const [params, setParams] = useSearchParams()
  const repositoryId = params.get('repository')
  const progress = useQuery({ queryKey: ['progress', repositoryId], queryFn: () => getProgress(repositoryId), retry: false })

  return (
    <div className="mx-auto w-full max-w-5xl px-4 py-10 md:py-16">
      <p className="text-xs uppercase tracking-[0.08em] text-muted">Progress</p>
      <h1 className="mt-2 font-display text-[2.5rem] leading-[1.05] tracking-[-0.015em] text-ink sm:text-5xl">
        {progress.data?.scope.repositoryName ?? 'Your engineering over time'}
      </h1>

      {progress.isPending && <p className="mt-8 text-sm text-muted">Loading progress…</p>}
      {progress.error && (
        <div role="alert" className="mt-8">
          <p className="text-ink">
            {progress.error instanceof ApiError ? progress.error.message : 'Something went wrong. Please try again.'}
          </p>
          {repositoryId && (
            <Link to="/progress" className="mt-3 inline-block text-sm text-muted underline underline-offset-4 hover:text-ink">
              Show all repositories
            </Link>
          )}
        </div>
      )}
      {progress.data && (
        <Content
          progress={progress.data}
          repositoryId={repositoryId}
          onScope={(id) => setParams(id ? { repository: id } : {}, { replace: true })}
        />
      )}
    </div>
  )
}

function Content({ progress: p, repositoryId, onScope }: { progress: Progress; repositoryId: string | null; onScope: (id: string | null) => void }) {
  const hasEvidence = p.evidence.reviews > 0 || p.evidence.labs > 0
  const names = Object.fromEntries(p.areas.map((a) => [a.area, a.name]))
  const areaName = (id: string) => names[id] ?? id

  return (
    <>
      {(p.repositories.length > 1 || repositoryId) && (
        <div className="mt-6 max-w-xs">
          <label htmlFor="progress-scope" className={labelClass}>
            Repositories
          </label>
          <select
            id="progress-scope"
            className={inputClass}
            value={repositoryId ?? ''}
            onChange={(e) => onScope(e.target.value || null)}
          >
            <option value="">All repositories</option>
            {p.repositories.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {!hasEvidence ? (
        <div className="mt-10 max-w-2xl border-t border-line pt-8">
          <h2 className="font-display text-2xl text-ink">Progress starts with your first review</h2>
          <p className="mt-3 text-sm leading-relaxed text-ink/85">
            Review a repository you built, then practise production problems from it in Scenario Lab. As reviews and labs
            build up, this page shows which engineering areas are consistently strong, which keep coming up as gaps, and
            how they change over time.
          </p>
          <Link to="/dashboard" className="mt-5 inline-block text-sm text-ink underline underline-offset-4">
            Review a repository
          </Link>
        </div>
      ) : (
        <>
          <EvidenceLine progress={p} />
          <div className="mt-12 space-y-16">
            <Section title="What the evidence shows">
              <Indicators areas={p.areas} />
            </Section>
            <Section title="Engineering areas">
              <AreaTable areas={p.areas} />
            </Section>
            <Section title="Scenario Lab practice">
              <Practice progress={p} areaName={areaName} />
            </Section>
            <Section title="Next areas to work on">
              <NextAreas progress={p} />
            </Section>
            <Section title="History">
              <ProgressHistory repositoryId={repositoryId} />
            </Section>
          </div>
        </>
      )}

      <p className="mt-16 max-w-3xl border-t border-line pt-6 text-xs leading-relaxed text-muted">
        Progress compares stored assessments using fixed, explainable rules. Indicators describe this evidence; they are not
        a measure of your overall engineering ability or industry readiness. Reviews assess the code in a repository, which
        may not all be yours; Scenario Lab assesses your own answers.
      </p>
    </>
  )
}

function EvidenceLine({ progress: p }: { progress: Progress }) {
  const e = p.evidence
  const parts = [
    e.reviews > 0 && `${e.reviews} ${e.reviews === 1 ? 'review' : 'reviews'}`,
    e.labs > 0 && `${e.labs} completed ${e.labs === 1 ? 'lab' : 'labs'} (${e.evaluatedAnswers} evaluated ${e.evaluatedAnswers === 1 ? 'answer' : 'answers'})`,
  ].filter(Boolean)
  const span = e.from && e.to ? (e.from.slice(0, 10) === e.to.slice(0, 10) ? formatDate(e.to) : `${formatDate(e.from)} – ${formatDate(e.to)}`) : ''
  return (
    <div className="mt-6 space-y-1 text-sm text-ink/85">
      <p>
        Based on {parts.join(' and ')}
        {e.repositories > 1 && ` across ${e.repositories} repositories`}
        {span && ` · ${span}`}.
      </p>
      {e.truncated && <p className="text-xs text-muted">Only the newest 50 reviews and 50 labs are included.</p>}
      {e.skipped > 0 && (
        <p className="text-xs text-muted">
          {e.skipped} stored {e.skipped === 1 ? 'assessment was' : 'assessments were'} left out because {e.skipped === 1 ? 'it uses' : 'they use'} an
          older format or couldn't be read.
        </p>
      )}
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2 className="mb-6 border-t border-line pt-6 font-display text-2xl text-ink">{title}</h2>
      {children}
    </section>
  )
}
