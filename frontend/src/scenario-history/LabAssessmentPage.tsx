import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ApiError, downloadFile, getLabAssessment, type LabAssessmentReport } from '../api'
import { AssessmentMark } from '../review/AssessmentMark'
import { titleCase } from '../review/assessment'
import { rolesLabel, SENIORITY_LABELS } from '../scenario-lab/options'
import { AiText, RevealScope } from '../shared/Reveal'
import { Bullets, ScenarioReport } from './ScenarioReport'
import { Button, ErrorBanner } from '../ui'

const dateFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'long' })

/**
 * A completed lab, read-only: the permanent record of how the developer engineered this project at that
 * commit. It never reopens the workspace or an editor.
 */
export function LabAssessmentPage() {
  const { labId = '' } = useParams()
  const report = useQuery({ queryKey: ['lab-assessment', labId], queryFn: () => getLabAssessment(labId), retry: false })

  return (
    <div className="mx-auto w-full max-w-4xl px-4 py-10 md:py-16">
      {report.isPending && <p className="text-sm text-muted">Loading assessment…</p>}
      {report.error && (
        <div role="alert">
          <h1 className="font-display text-3xl text-ink">
            {report.error instanceof ApiError ? report.error.message : 'Something went wrong. Please try again.'}
          </h1>
          <Link to="/dashboard" className="mt-4 inline-block text-sm text-muted underline underline-offset-4 hover:text-ink">
            Back to the dashboard
          </Link>
        </div>
      )}
      {report.data && <Report report={report.data} />}
    </div>
  )
}

function Report({ report: r }: { report: LabAssessmentReport }) {
  const a = r.assessment
  const pdf = useMutation({
    mutationFn: () => downloadFile(`/api/scenario-labs/${r.id}/assessment/pdf`, `engiens-scenario-lab-${r.number}.pdf`),
  })
  const back = r.reviewId ? `/reviews/${r.reviewId}` : `/repositories/${r.repositoryId}`
  return (
    <article>
      <Link to={back} className="font-mono text-xs text-muted transition-colors hover:text-ink">
        ← {r.reviewId ? 'Back to review' : r.repositoryName}
      </Link>
      <p className="mt-6 text-xs uppercase tracking-[0.08em] text-muted">Scenario Lab assessment · Lab #{r.number}</p>
      <h1 className="mt-2 break-words font-display text-[2.75rem] leading-[1.05] tracking-[-0.015em] text-ink sm:text-6xl">
        {r.repositoryName}
      </h1>
      <dl className="mt-6 grid gap-x-8 gap-y-2 text-sm sm:grid-cols-2">
        <Meta label="Roles">{rolesLabel(r.roles)}</Meta>
        <Meta label="Seniority">{SENIORITY_LABELS[r.seniority]}</Meta>
        <Meta label="Scenarios">
          {r.scenarios.length} of {r.scenarioCount}
        </Meta>
        <Meta label="Completed">{dateFormat.format(new Date(r.completedAt))}</Meta>
        <Meta label="Commit">
          <span className="font-mono text-[13px]">{r.commitSha.slice(0, 7)}</span>
        </Meta>
        {a.personalizedFor && <Meta label="Learning points for">{a.personalizedFor}</Meta>}
      </dl>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <Button type="button" variant="secondary" busy={pdf.isPending} onClick={() => pdf.mutate()}>
          Export PDF
        </Button>
        <ErrorBanner error={pdf.error} />
      </div>

      <RevealScope id={`lab:${r.id}`} className="mt-14 space-y-16">
        <Section title="Overall assessment">
          <AiText as="p" text={a.overallAssessment.summary} className="max-w-3xl text-[17px] leading-relaxed text-ink/90" />
          <p className="mt-4 text-sm leading-relaxed">
            <span className="text-muted">Against the target level: </span>
            <AiText text={a.overallAssessment.engineeringLevel} className="text-ink/90" />
          </p>
          <p className="mt-2 text-xs text-muted">{titleCase(a.overallAssessment.confidence)} confidence</p>
          <div className="mt-8 grid gap-8 sm:grid-cols-2">
            <Bullets title="Strengths" items={a.strengths} />
            <Bullets title="Growth areas" items={a.growthAreas} />
          </div>
        </Section>

        <Section title="Scenario results">
          <ol className="divide-y divide-line border-y border-line">
            {r.scenarios.map((s) => (
              <li key={s.scenarioId}>
                <a href={`#scenario-${s.position}`} className="flex items-center justify-between gap-4 py-3 text-sm text-ink hover:text-white">
                  <span>
                    <span className="mr-3 font-mono text-muted">{s.position}</span>
                    {s.title}
                  </span>
                  {s.evaluation && <AssessmentMark value={s.evaluation.verdict} />}
                </a>
              </li>
            ))}
          </ol>
          <div className="mt-12 space-y-20">
            {r.scenarios.map((s) => (
              <ScenarioReport key={s.scenarioId} s={s} />
            ))}
          </div>
        </Section>

        {a.learningRecommendations.length > 0 && (
          <Section title="Learning recommendations">
            <ol className="space-y-5">
              {a.learningRecommendations.map((rec, i) => (
                <li key={i} className="grid grid-cols-[2rem_1fr] gap-2 text-sm">
                  <span className="font-display text-xl text-muted">{i + 1}</span>
                  <div>
                    <p className="text-[16px] text-ink">{rec.topic}</p>
                    <AiText as="p" text={rec.why} className="mt-1 leading-relaxed text-ink/85" />
                    <AiText as="p" text={rec.connectionToProject} className="mt-1 leading-relaxed text-muted" />
                  </div>
                </li>
              ))}
            </ol>
          </Section>
        )}

        {a.limitations.length > 0 && (
          <Section title="Limitations">
            <ul className="list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-muted marker:text-line-strong">
              {a.limitations.map((l, i) => (
                <li key={i}>
                  <AiText text={l} />
                </li>
              ))}
              <li>Scenario Lab evaluates engineering judgement on problems from this repository; it isn’t a measure of industry readiness.</li>
            </ul>
          </Section>
        )}
      </RevealScope>

      <div className="mt-16 flex flex-wrap gap-3 border-t border-line pt-8">
        <Button type="button" variant="secondary" busy={pdf.isPending} onClick={() => pdf.mutate()}>
          Export PDF
        </Button>
        <Link to={back} className="inline-flex h-9 items-center rounded-md px-4 text-sm text-muted hover:text-ink">
          {r.reviewId ? 'Back to repository review' : 'Back to repository'}
        </Link>
      </div>
    </article>
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

function Meta({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex gap-3">
      <dt className="w-36 shrink-0 text-muted">{label}</dt>
      <dd className="text-ink/90">{children}</dd>
    </div>
  )
}
