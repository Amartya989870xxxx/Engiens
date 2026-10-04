import type { ReactNode } from 'react'
import type { ScenarioDocument } from '../api'

/** The scenario as the developer reads it: incident, context, task, evidence. Never hidden checks. */
export function ScenarioProblem({ document: d }: { document: ScenarioDocument }) {
  return (
    <div className="space-y-7">
      <Part title="What’s happening">
        <p className="whitespace-pre-line text-[15px] leading-relaxed text-ink/90">{d.incident}</p>
      </Part>
      <Part title="Context">
        <p className="whitespace-pre-line text-sm leading-relaxed text-ink/85">{d.context}</p>
      </Part>
      <Part title="Your task">
        <p className="whitespace-pre-line text-sm leading-relaxed text-ink">{d.task}</p>
      </Part>
      <List title="Expected behaviour" items={d.expectedBehaviour} />
      <List title="Constraints" items={d.constraints} />
      {d.evidence.length > 0 && (
        <Part title="Based on">
          <ul className="space-y-2.5 text-sm">
            {d.evidence.map((e, i) => (
              <li key={i}>
                <span className="font-mono text-[12px] text-ink">
                  {e.file}
                  {e.lineStart ? `:${e.lineStart}${e.lineEnd && e.lineEnd !== e.lineStart ? `–${e.lineEnd}` : ''}` : ''}
                </span>
                <span className="mt-0.5 block leading-relaxed text-muted">{e.explanation}</span>
              </li>
            ))}
          </ul>
        </Part>
      )}
    </div>
  )
}

function Part({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2 className="mb-2 text-xs font-medium uppercase tracking-[0.08em] text-muted">{title}</h2>
      {children}
    </section>
  )
}

function List({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null
  return (
    <Part title={title}>
      <ul className="list-disc space-y-1 pl-5 text-sm leading-relaxed text-ink/85 marker:text-muted">
        {items.map((it, i) => (
          <li key={i}>{it}</li>
        ))}
      </ul>
    </Part>
  )
}
