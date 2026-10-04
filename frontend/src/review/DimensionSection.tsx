import type { DimensionReview } from '../api'
import { AssessmentMark } from './AssessmentMark'
import { titleCase } from './assessment'
import { EvidenceList } from './Evidence'

const SCALE_ROWS = [
  ['currentScale', 'Today'],
  ['tenX', '10× growth'],
  ['hundredX', '100× growth'],
  ['largeScale', 'Large scale'],
] as const

/** One rubric dimension: collapsed to a single line until opened from the index or its header. */
export function DimensionSection({
  reviewId,
  dimension: d,
  open,
  onToggle,
}: {
  reviewId: string
  dimension: DimensionReview
  open: boolean
  onToggle: () => void
}) {
  return (
    <section id={`dim-${d.id}`} className="scroll-mt-6 border-t border-line" aria-labelledby={`dim-${d.id}-title`}>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-4 py-4 text-left focus-visible:outline-2 focus-visible:outline-white"
      >
        <h3 id={`dim-${d.id}-title`} className="font-display text-xl text-ink">
          {d.name}
        </h3>
        <span className="flex items-center gap-4">
          <AssessmentMark value={d.assessment} />
          <span aria-hidden className="text-muted">
            {open ? '−' : '+'}
          </span>
        </span>
      </button>
      {open && (
        <div className="space-y-8 pb-10 animate-fade-in motion-reduce:animate-none">
          <p className="max-w-3xl text-[15px] leading-relaxed text-ink/90">{d.summary}</p>
          {d.assessment !== 'NOT_ASSESSABLE' && (
            <p className="text-xs text-muted">Confidence: {titleCase(d.confidence)}</p>
          )}

          {d.strengths.length > 0 && (
            <Block title="What you did well">
              {d.strengths.map((s, i) => (
                <div key={i}>
                  <p className="text-ink">{s.title}</p>
                  <p className="mt-1 text-sm leading-relaxed text-muted">{s.description}</p>
                  <EvidenceList reviewId={reviewId} evidence={s.evidence} />
                </div>
              ))}
            </Block>
          )}

          {d.concerns.length > 0 && (
            <Block title="What could improve">
              {d.concerns.map((c) => (
                <article key={c.id} className="border-l border-line-strong pl-5">
                  <p className="font-mono text-[11px] uppercase tracking-wider text-muted">
                    {c.id} · {titleCase(c.severity)} severity · {titleCase(c.confidence)} confidence
                  </p>
                  <h4 className="mt-1 text-[17px] text-ink">{c.title}</h4>
                  <p className="mt-2 text-sm leading-relaxed text-ink/85">{c.description}</p>
                  <dl className="mt-4 space-y-3 text-sm">
                    <Row label="Why it matters">{c.whyItMatters}</Row>
                    <Row label="Engineering impact">{c.engineeringImpact}</Row>
                    <Row label="Recommendation">{c.recommendation}</Row>
                    {c.suggestedDirection && <Row label="Suggested direction">{c.suggestedDirection}</Row>}
                  </dl>
                  {c.scaleImpact && SCALE_ROWS.some(([k]) => c.scaleImpact?.[k]) && (
                    <div className="mt-4">
                      <p className="text-xs text-muted">
                        As the system grows (scenarios, not predictions
                        {c.scaleImpact.confidence ? `; ${titleCase(c.scaleImpact.confidence).toLowerCase()} confidence` : ''})
                      </p>
                      <dl className="mt-2 divide-y divide-line border-y border-line text-sm">
                        {SCALE_ROWS.filter(([k]) => c.scaleImpact?.[k]).map(([k, label]) => (
                          <div key={k} className="grid gap-1 py-2 sm:grid-cols-[8rem_1fr] sm:gap-4">
                            <dt className="text-muted">{label}</dt>
                            <dd className="text-ink/85">{c.scaleImpact?.[k]}</dd>
                          </div>
                        ))}
                      </dl>
                    </div>
                  )}
                  {c.learningValue && (
                    <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-3">
                      <Stacked label="At your level">{c.learningValue.currentLevel}</Stacked>
                      <Stacked label="Next level">{c.learningValue.nextLevel}</Stacked>
                      <Stacked label="Advanced">{c.learningValue.advancedLevel}</Stacked>
                    </dl>
                  )}
                  <EvidenceList reviewId={reviewId} evidence={c.evidence} />
                </article>
              ))}
            </Block>
          )}

          {d.tradeoffs.length > 0 && (
            <Block title="Trade-offs">
              {d.tradeoffs.map((t, i) => (
                <div key={i} className="text-sm">
                  <p className="text-ink">
                    {t.decision} <span className="ml-2 text-xs text-muted">{titleCase(t.assessment)}</span>
                  </p>
                  <p className="mt-1 text-muted">
                    <span className="text-ink/70">Benefit:</span> {t.benefit}
                  </p>
                  <p className="text-muted">
                    <span className="text-ink/70">Cost:</span> {t.cost}
                  </p>
                </div>
              ))}
            </Block>
          )}

          {d.personalizedAdvice.length > 0 && (
            <Block title="For you">
              <ul className="list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-ink/90 marker:text-muted">
                {d.personalizedAdvice.map((a, i) => (
                  <li key={i}>{a}</li>
                ))}
              </ul>
            </Block>
          )}
        </div>
      )}
    </section>
  )
}

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h4 className="mb-3 text-xs font-medium uppercase tracking-[0.08em] text-muted">{title}</h4>
      <div className="space-y-6">{children}</div>
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1 sm:grid-cols-[10rem_1fr] sm:gap-4">
      <dt className="text-muted">{label}</dt>
      <dd className="leading-relaxed text-ink/85">{children}</dd>
    </div>
  )
}

function Stacked({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-1 leading-relaxed text-ink/85">{children}</dd>
    </div>
  )
}
