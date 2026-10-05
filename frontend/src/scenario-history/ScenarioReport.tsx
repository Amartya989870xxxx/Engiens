import { useState, type ReactNode } from 'react'
import type { ScenarioResult } from '../api'
import { AssessmentMark } from '../review/AssessmentMark'
import { titleCase } from '../review/assessment'
import { humanize } from '../scenario-lab/options'
import { RunResults } from '../scenario-lab/RunResults'
import { AiText } from '../shared/Reveal'

/**
 * One submitted scenario, read-only: what was submitted, the objective results, the evaluation and the reference.
 * Used by the lab's full assessment and by a single scenario's feedback while the lab is still open.
 */
export function ScenarioReport({ s }: { s: ScenarioResult }) {
  const [showReference, setShowReference] = useState(false)
  const e = s.evaluation
  return (
    <article id={`scenario-${s.position}`} className="scroll-mt-6">
      <p className="font-mono text-[11px] uppercase tracking-wider text-muted">
        Scenario {s.position} · {humanize(s.category)} · {humanize(s.difficulty)} · answered in {s.mode === 'CODE' ? 'code' : 'approach'} mode
      </p>
      <h3 className="mt-2 font-display text-2xl text-ink">{s.title}</h3>
      <p className="mt-3 text-sm leading-relaxed text-muted">{s.document.summary}</p>

      <Block title="Your implementation" show={s.submittedFiles.length > 0}>
        {s.submittedFiles.map((f) => (
          <div key={f.path} className="mt-2">
            <p className="font-mono text-[11px] text-muted">{f.path}</p>
            <pre className="mt-1 max-h-96 overflow-auto rounded-md border border-line bg-canvas p-3 font-mono text-[12px] leading-relaxed text-ink/85">
              {f.content}
            </pre>
          </div>
        ))}
      </Block>
      <Block title="Your reasoning" show={Boolean(s.submittedApproach)}>
        <p className="whitespace-pre-line text-sm leading-relaxed text-ink/85">{s.submittedApproach}</p>
      </Block>
      <Block title="Execution results" show={s.executionCapability === 'CODE'}>
        {s.runResult ? <RunResults result={s.runResult} /> : <p className="text-sm text-muted">The checks couldn’t be run when this was submitted.</p>}
      </Block>

      {e && (
        <div className="mt-8 space-y-6">
          <div className="flex flex-wrap items-center gap-4">
            <AssessmentMark value={e.verdict} />
            <span className="text-xs text-muted">{titleCase(e.confidence)} confidence</span>
          </div>
          <AiText as="p" text={e.assessment} className="max-w-3xl text-[15px] leading-relaxed text-ink/90" />
          <div className="grid gap-8 sm:grid-cols-2">
            <Bullets title="What you got right" items={e.whatWasCorrect} />
            <Bullets title="What you missed" items={e.whatWasMissed} />
          </div>
          <dl className="divide-y divide-line border-y border-line">
            <Row label="Root cause" text={e.rootCause} />
            <Row label="Engineering judgement" text={e.engineeringJudgment} />
            <Row label="At larger scale" text={e.scaleImpact} />
            <Row label="Regression risk" text={e.regressionRisk} />
            <Row label="Testing" text={e.testingAssessment} />
            <Row label="Recommended fix" text={e.recommendedFix} />
            <Row label="A stronger approach" text={e.referenceApproach} />
          </dl>
          <Bullets title="Trade-offs" items={e.tradeoffs} />
          <Bullets title="What to learn" items={s.learningPoints} />
        </div>
      )}

      {s.reference && (
        <div className="mt-6">
          <button type="button" onClick={() => setShowReference((v) => !v)} aria-expanded={showReference} className="text-xs text-muted hover:text-ink">
            {showReference ? 'Hide the reference approach' : 'Show the reference approach'}
          </button>
          {showReference && (
            <div className="mt-3 space-y-3">
              <p className="text-sm leading-relaxed text-ink/85">{s.reference.referenceReasoning}</p>
              {s.reference.solution?.files.map((f) => (
                <div key={f.path}>
                  <p className="font-mono text-[11px] text-muted">{f.path} (one valid solution)</p>
                  <pre className="mt-1 max-h-96 overflow-auto rounded-md border border-line bg-canvas p-3 font-mono text-[12px] leading-relaxed text-ink/80">
                    {f.content}
                  </pre>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </article>
  )
}

function Block({ title, show, children }: { title: string; show: boolean; children: ReactNode }) {
  if (!show) return null
  return (
    <div className="mt-6">
      <h4 className="mb-2 text-xs font-medium uppercase tracking-[0.08em] text-muted">{title}</h4>
      {children}
    </div>
  )
}

function Row({ label, text }: { label: string; text: string }) {
  return (
    <div className="grid gap-1 py-3 text-sm sm:grid-cols-[11rem_1fr] sm:gap-6">
      <dt className="text-muted">{label}</dt>
      <AiText as="dd" text={text} className="leading-relaxed text-ink/85" />
    </div>
  )
}

export function Bullets({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null
  return (
    <div className="text-sm">
      <h4 className="mb-3 text-xs font-medium uppercase tracking-[0.08em] text-muted">{title}</h4>
      <ul className="list-disc space-y-1.5 pl-5 leading-relaxed text-ink/85 marker:text-muted">
        {items.map((it, i) => (
          <li key={i}>
            <AiText text={it} />
          </li>
        ))}
      </ul>
    </div>
  )
}
