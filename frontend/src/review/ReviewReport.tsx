import { useState, type ReactNode } from 'react'
import type { ReviewDocument } from '../api'
import { AssessmentMark } from './AssessmentMark'
import { ASSESSMENT, titleCase } from './assessment'
import { DimensionSection } from './DimensionSection'
import { EvidenceList } from './Evidence'

const SCALE_AREAS = [
  ['trafficGrowth', 'Traffic growth'],
  ['dataGrowth', 'Data growth'],
  ['concurrency', 'Concurrency'],
  ['failureRecovery', 'Failure recovery'],
  ['operationalComplexity', 'Operational complexity'],
] as const

/** The whole engineering report, in reading order. All model text is rendered as plain text. */
export function ReviewReport({ reviewId, review }: { reviewId: string; review: ReviewDocument }) {
  const [open, setOpen] = useState<Set<string>>(new Set())
  const toggle = (id: string) =>
    setOpen((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  const openFromIndex = (id: string) => {
    setOpen((prev) => new Set(prev).add(id))
    // Wait for the section to expand, then bring it into view.
    requestAnimationFrame(() => document.getElementById(`dim-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }
  const o = review.overallAssessment
  const e = review.executiveSummary

  return (
    <div className="space-y-16">
      <section aria-labelledby="overall">
        <h2 id="overall" className="sr-only">
          Overall assessment
        </h2>
        <p className="text-xs uppercase tracking-[0.08em] text-muted">Overall engineering assessment</p>
        <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2">
          <span className="font-display text-4xl text-ink">{ASSESSMENT[o.level].label}</span>
          <AssessmentMark value={o.level} showLabel={false} />
          <span className="text-xs text-muted">{titleCase(o.confidence)} confidence</span>
        </div>
        <p className="mt-4 max-w-3xl text-[17px] leading-relaxed text-ink/90">{o.summary}</p>
      </section>

      <Section title="Executive summary">
        <dl className="divide-y divide-line border-y border-line">
          <SummaryRow label="What this project does">{e.whatThisProjectDoes}</SummaryRow>
          <SummaryRow label="Engineering summary">{e.engineeringSummary}</SummaryRow>
          <SummaryRow label="Strongest aspect">{e.strongestAspect}</SummaryRow>
          <SummaryRow label="Biggest opportunity">{e.biggestOpportunity}</SummaryRow>
          <SummaryRow label="As it grows">{e.overallScaleConcern}</SummaryRow>
        </dl>
      </Section>

      {review.positiveHighlights.length > 0 && (
        <Section title="What you did well">
          <div className="grid gap-8 sm:grid-cols-2">
            {review.positiveHighlights.map((h, i) => (
              <div key={i}>
                <p className="text-[17px] text-ink">{h.title}</p>
                <p className="mt-2 text-sm leading-relaxed text-ink/85">{h.description}</p>
                <p className="mt-2 text-sm leading-relaxed text-muted">{h.whyThisIsGood}</p>
                <EvidenceList reviewId={reviewId} evidence={h.evidence} />
              </div>
            ))}
          </div>
        </Section>
      )}

      <Section title="Engineering review index">
        <ul aria-label="Dimensions" className="divide-y divide-line border-y border-line">
          {review.dimensions.map((d) => (
            <li key={d.id}>
              <button
                type="button"
                onClick={() => openFromIndex(d.id)}
                className="flex w-full items-center justify-between gap-4 py-2.5 text-left text-sm text-ink transition-colors hover:text-white focus-visible:outline-2 focus-visible:outline-white"
              >
                <span>{d.name}</span>
                <AssessmentMark value={d.assessment} />
              </button>
            </li>
          ))}
        </ul>
        <div className="mt-12">
          <div className="mb-2 flex justify-end">
            <button
              type="button"
              onClick={() => setOpen(open.size === review.dimensions.length ? new Set() : new Set(review.dimensions.map((d) => d.id)))}
              className="text-xs text-muted hover:text-ink"
            >
              {open.size === review.dimensions.length ? 'Collapse all' : 'Expand all'}
            </button>
          </div>
          {review.dimensions.map((d) => (
            <DimensionSection key={d.id} reviewId={reviewId} dimension={d} open={open.has(d.id)} onToggle={() => toggle(d.id)} />
          ))}
          <div className="border-t border-line" />
        </div>
      </Section>

      {review.crossCuttingFindings.length > 0 && (
        <Section title="Across the codebase">
          <div className="space-y-8">
            {review.crossCuttingFindings.map((f) => (
              <article key={f.id} className="border-l border-line-strong pl-5">
                <p className="font-mono text-[11px] uppercase tracking-wider text-muted">
                  {f.id} · {titleCase(f.severity)} severity · {titleCase(f.confidence)} confidence
                </p>
                <h3 className="mt-1 text-[17px] text-ink">{f.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-ink/85">{f.description}</p>
                <p className="mt-2 text-sm leading-relaxed text-muted">{f.whyItMatters}</p>
                <p className="mt-2 text-sm leading-relaxed text-ink/85">
                  <span className="text-muted">Recommendation: </span>
                  {f.recommendation}
                </p>
                {f.exampleApproach && (
                  <pre className="mt-3 overflow-auto whitespace-pre-wrap rounded-md border border-line bg-canvas p-3 font-mono text-[12px] text-ink/85">
                    {f.exampleApproach}
                  </pre>
                )}
                <EvidenceList reviewId={reviewId} evidence={f.evidence} />
              </article>
            ))}
          </div>
        </Section>
      )}

      {review.featureEngineeringReview.length > 0 && (
        <Section title="Feature engineering">
          <div className="space-y-10">
            {review.featureEngineeringReview.map((f) => (
              <article key={f.feature}>
                <h3 className="font-display text-xl text-ink">{f.feature}</h3>
                <dl className="mt-4 grid gap-6 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="flex items-center justify-between text-muted">
                      Correctness <AssessmentMark value={f.correctness.assessment} />
                    </dt>
                    <dd className="mt-2 leading-relaxed text-ink/85">{f.correctness.summary}</dd>
                  </div>
                  <div>
                    <dt className="flex items-center justify-between text-muted">
                      Implementation <AssessmentMark value={f.implementationQuality.assessment} />
                    </dt>
                    <dd className="mt-2 leading-relaxed text-ink/85">{f.implementationQuality.summary}</dd>
                  </div>
                </dl>
                <div className="mt-6 grid gap-6 text-sm sm:grid-cols-2">
                  <Bullets title="Edge cases handled" items={f.edgeCases.handled} />
                  <Bullets title="Edge cases missing" items={f.edgeCases.missing} />
                  <Bullets title="Failure modes" items={f.failureModes} />
                  <Bullets title="At larger scale" items={f.scaleConsiderations} />
                </div>
                <Bullets title="Recommendations" items={f.recommendations} className="mt-6 text-sm" />
                <EvidenceList reviewId={reviewId} evidence={f.evidence} />
              </article>
            ))}
          </div>
        </Section>
      )}

      <Section title="Scale readiness">
        <p className="max-w-3xl text-[15px] leading-relaxed text-ink/90">{review.scaleReadiness.summary}</p>
        <p className="mt-2 text-xs text-muted">Engineering scenarios from the code, not measurements: runtime load wasn't observed.</p>
        <dl className="mt-6 divide-y divide-line border-y border-line">
          {SCALE_AREAS.map(([key, label]) => {
            const area = review.scaleReadiness[key]
            return (
              <div key={key} className="grid gap-2 py-4 sm:grid-cols-[12rem_10rem_1fr] sm:gap-6">
                <dt className="text-sm text-ink">{label}</dt>
                <dd>
                  <AssessmentMark value={area.assessment} />
                </dd>
                <dd className="text-sm leading-relaxed text-muted">{area.concerns.join(' ')}</dd>
              </div>
            )
          })}
        </dl>
        {review.scaleReadiness.mostLikelyBottlenecks.length > 0 && (
          <div className="mt-6">
            <h3 className="mb-3 text-xs font-medium uppercase tracking-[0.08em] text-muted">Most likely bottlenecks</h3>
            <ul className="space-y-3 text-sm">
              {review.scaleReadiness.mostLikelyBottlenecks.map((b, i) => (
                <li key={i}>
                  <span className="text-ink">{b.component}</span>
                  <span className="text-muted"> · {b.reason}</span>
                  <span className="ml-2 text-xs text-muted">({titleCase(b.confidence).toLowerCase()} confidence)</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </Section>

      {review.priorityActions.length > 0 && (
        <Section title="Priority actions">
          <ol className="space-y-6">
            {[...review.priorityActions]
              .sort((a, b) => a.priority - b.priority)
              .map((a) => (
                <li key={a.priority} className="grid grid-cols-[2.5rem_1fr] gap-2">
                  <span className="font-display text-2xl text-muted">{a.priority}</span>
                  <div>
                    <p className="text-[17px] text-ink">{a.title}</p>
                    <p className="mt-1 text-sm leading-relaxed text-ink/85">{a.reason}</p>
                    <p className="mt-1 text-sm leading-relaxed text-muted">{a.expectedBenefit}</p>
                    <p className="mt-1 text-xs text-muted">Difficulty: {titleCase(a.difficulty)}</p>
                  </div>
                </li>
              ))}
          </ol>
        </Section>
      )}

      <Section title="Your learning plan">
        <div className="grid gap-10 sm:grid-cols-3">
          <Bullets title="You already do well" items={review.personalizedLearningPlan.youAlreadyDoWell} className="text-sm" />
          <div className="text-sm">
            <h3 className="mb-3 text-xs font-medium uppercase tracking-[0.08em] text-muted">Next things to learn</h3>
            <ol className="space-y-4">
              {[...review.personalizedLearningPlan.nextThingsToLearn]
                .sort((a, b) => a.suggestedOrder - b.suggestedOrder)
                .map((t) => (
                  <li key={t.topic}>
                    <p className="text-ink">{t.topic}</p>
                    <p className="mt-1 leading-relaxed text-muted">{t.why}</p>
                    <p className="mt-1 leading-relaxed text-ink/75">{t.connectionToProject}</p>
                  </li>
                ))}
            </ol>
          </div>
          <Bullets title="Advanced topics" items={review.personalizedLearningPlan.advancedTopics} className="text-sm" />
        </div>
      </Section>

      <Section title="What this review couldn't determine">
        <ul className="list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-muted marker:text-line-strong">
          {review.reviewLimitations.map((l, i) => (
            <li key={i}>{l}</li>
          ))}
          <li>Engiens reviewed selected files from one commit against industry-oriented software engineering practices; it is not a full audit.</li>
        </ul>
        {review.personalization && (
          <p className="mt-4 text-xs text-muted">Advice written for: {review.personalization.basis.toLowerCase()}.</p>
        )}
      </Section>
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

function SummaryRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 py-3 sm:grid-cols-[12rem_1fr] sm:gap-6">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="text-[15px] leading-relaxed text-ink/90">{children}</dd>
    </div>
  )
}

function Bullets({ title, items, className = '' }: { title: string; items: string[]; className?: string }) {
  if (items.length === 0) return null
  return (
    <div className={className}>
      <h3 className="mb-3 text-xs font-medium uppercase tracking-[0.08em] text-muted">{title}</h3>
      <ul className="list-disc space-y-1.5 pl-5 leading-relaxed text-ink/85 marker:text-muted">
        {items.map((it, i) => (
          <li key={i}>{it}</li>
        ))}
      </ul>
    </div>
  )
}
