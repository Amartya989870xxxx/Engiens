import { useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  cancelLab,
  finishLab,
  getActiveLab,
  getLab,
  retryEvaluation,
  retryFinalization,
  type ScenarioLab,
  type ScenarioSummary,
} from '../api'
import { Button, ErrorBanner } from '../ui'
import { lastLab, rememberLab } from './lastLab'
import { LabSetup } from './LabSetup'
import { generationProgress } from './progress'
import { humanize, LANGUAGE_LABELS, ROLE_LABELS, rolesLabel, SENIORITY_LABELS } from './options'

const isBusy = (lab: ScenarioLab | null | undefined) =>
  Boolean(lab && (lab.status === 'GENERATING' || lab.status === 'FINALIZING' || lab.scenarios.some((s) => s.evaluationStatus === 'PENDING')))

/**
 * Scenario Lab is a temporary workspace: with an open lab it shows that lab; otherwise it shows the start
 * screen. Finished labs never appear here: they live in the repository's history.
 */
export function ScenarioLabPage() {
  const [params] = useSearchParams()
  const queryClient = useQueryClient()
  const [, rerender] = useState(0)
  const active = useQuery({
    queryKey: ['scenario-lab', 'active'],
    queryFn: getActiveLab,
    refetchInterval: (q) => (isBusy(q.state.data) ? 3000 : false),
  })
  // Remember the open lab, so when it finishes in the background this page can show how it ended.
  const openId = active.data?.id
  useEffect(() => {
    if (openId) rememberLab(openId)
  }, [openId])
  const last = openId ?? lastLab()
  const ended = useQuery({
    queryKey: ['scenario-lab', 'lab', last],
    queryFn: () => getLab(last!),
    enabled: Boolean(last) && active.data === null,
    retry: false,
  })

  const started = (lab: ScenarioLab) => {
    rememberLab(lab.id)
    rerender((n) => n + 1)
    void queryClient.invalidateQueries({ queryKey: ['scenario-lab'] })
  }
  const dismiss = () => {
    rememberLab(null)
    rerender((n) => n + 1)
  }

  if (active.isPending) return <Page><p className="text-sm text-muted">Loading Scenario Lab…</p></Page>
  if (active.error) return <Page><ErrorBanner error={active.error} /></Page>
  if (active.data) return <Page wide><LabSession lab={active.data} /></Page>
  if (ended.data?.status === 'COMPLETED') return <Navigate to={`/scenario-lab/complete/${ended.data.id}`} replace />

  const failed = ended.data?.status === 'FAILED' ? ended.data : null
  return (
    <Page>
      <p className="text-xs uppercase tracking-[0.08em] text-muted">Scenario Lab</p>
      <h1 className="mt-3 font-display text-[2.6rem] leading-[1.05] tracking-[-0.015em] text-ink sm:text-5xl">
        Test your engineering judgement against a real codebase.
      </h1>
      <p className="mt-5 max-w-2xl text-[15px] leading-relaxed text-muted">
        Engiens reads a repository and writes realistic production problems from its own code. Solve them in code or explain
        your approach, run the checks, and get an evaluation of how you think, not just what you type.
      </p>
      {failed && (
        <div role="alert" className="mt-8 rounded-lg border border-danger/30 bg-danger/5 p-4">
          <p className="text-sm text-ink">Your last lab couldn’t be generated: {failed.errorMessage ?? 'please try again.'}</p>
          <button type="button" onClick={dismiss} className="mt-2 text-xs text-muted underline underline-offset-4 hover:text-ink">
            Dismiss
          </button>
        </div>
      )}
      <div className="mt-12 border-t border-line pt-10">
        <LabSetup reviewId={params.get('review')} onStarted={started} />
      </div>
    </Page>
  )
}

function Page({ children, wide = false }: { children: React.ReactNode; wide?: boolean }) {
  return <div className={`mx-auto w-full px-4 py-10 md:py-16 ${wide ? 'max-w-5xl' : 'max-w-3xl'}`}>{children}</div>
}

function LabSession({ lab }: { lab: ScenarioLab }) {
  const location = useLocation()
  const submitted = (location.state as { submitted?: string } | null)?.submitted
  return (
    <div>
      <p className="text-xs uppercase tracking-[0.08em] text-muted">Scenario Lab · in progress</p>
      <h1 className="mt-3 font-display text-4xl leading-tight text-ink">{lab.repositoryName ?? 'Repository'}</h1>
      <p className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-sm text-muted">
        <span>{rolesLabel(lab.roles)}</span>
        <span aria-hidden>·</span>
        <span>{SENIORITY_LABELS[lab.seniority]}</span>
        <span aria-hidden>·</span>
        <span>{lab.scenarioCount} scenarios</span>
        <span aria-hidden>·</span>
        <span>
          commit <span className="font-mono text-[13px] text-ink/80">{lab.commitSha.slice(0, 7)}</span>
        </span>
      </p>
      <div className="mt-10">
        {lab.status === 'GENERATING' && <Generating lab={lab} submitted={submitted} />}
        {lab.status === 'ACTIVE' && <Scenarios lab={lab} submitted={submitted} />}
        {lab.status === 'FINALIZING' && <Finalizing lab={lab} />}
      </div>
    </div>
  )
}

/**
 * Generating: ready scenarios can be opened right away while the rest are built. Rows still being generated are
 * shown but can't be selected. Finishing waits until generation has ended.
 */
function Generating({ lab, submitted }: { lab: ScenarioLab; submitted?: string }) {
  return (
    <div>
      <div className="border-t border-line pt-8">
        <p role="status" className="flex items-center gap-2.5 text-ink">
          <span aria-hidden className="size-1.5 animate-pulse rounded-full bg-ink motion-reduce:animate-none" />
          {generationProgress(lab)}
        </p>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-muted">
          Every executable scenario is run in a sandbox before it appears: its starter code must reproduce the problem and a
          reference solution must pass its checks. Start on any ready scenario now; the rest keep generating. You can leave
          this page.
        </p>
      </div>
      <ScenarioList lab={lab} submitted={submitted} />
      <p className="mt-4 text-xs leading-relaxed text-muted">You can finish the lab once generation has ended.</p>
      <EndLab lab={lab} label="Stop generating" />
    </div>
  )
}

function Scenarios({ lab, submitted }: { lab: ScenarioLab; submitted?: string }) {
  const done = lab.scenarios.filter((s) => s.submitted).length
  return (
    <div>
      {lab.generationNote && (
        <p role="note" className="mb-6 rounded-md border border-line px-3 py-2 text-sm text-ink">
          {lab.generationNote} Only these can be solved and assessed.
        </p>
      )}
      <ScenarioList lab={lab} submitted={submitted} />
      <p className="mt-4 text-xs leading-relaxed text-muted">
        Submit every scenario, or finish early with what you’ve submitted. Your assessment is then saved to the repository’s
        history.
      </p>
      {done > 0 ? <FinishEarly lab={lab} submitted={done} /> : <EndLab lab={lab} label="Discard this lab" />}
    </div>
  )
}

/** Ready scenarios (selectable) followed, while generating, by placeholders for the ones not built yet. */
function ScenarioList({ lab, submitted }: { lab: ScenarioLab; submitted?: string }) {
  const done = lab.scenarios.filter((s) => s.submitted).length
  const pending = lab.status === 'GENERATING' ? Math.max(0, lab.scenarioCount - lab.scenarios.length) : 0
  return (
    <div className="mt-6">
      {submitted && (
        <p role="status" className="mb-6 text-sm text-muted">
          Submitted “{submitted}”. It’s being evaluated in the background.
        </p>
      )}
      <div className="flex items-baseline justify-between border-t border-line pt-6">
        <h2 className="font-display text-2xl text-ink">Scenarios</h2>
        <p className="text-sm text-muted">
          {done} of {lab.scenarios.length} submitted
        </p>
      </div>
      <ol className="mt-4 divide-y divide-line border-y border-line">
        {lab.scenarios.map((s) => (
          <ScenarioRow key={s.id} lab={lab} scenario={s} />
        ))}
        {Array.from({ length: pending }, (_, i) => (
          <li key={`pending-${i}`} aria-disabled className="grid grid-cols-[2rem_1fr_auto] items-center gap-3 py-4">
            <span className="font-display text-xl text-muted/50">{lab.scenarios.length + i + 1}</span>
            <span className="h-3 w-2/3 rounded bg-raised motion-safe:animate-pulse" aria-hidden />
            <span className="text-[13px] text-muted/70">Generating…</span>
          </li>
        ))}
      </ol>
    </div>
  )
}

function ScenarioRow({ lab, scenario: s }: { lab: ScenarioLab; scenario: ScenarioSummary }) {
  const queryClient = useQueryClient()
  const retry = useMutation({
    mutationFn: () => retryEvaluation(lab.id, s.id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['scenario-lab'] }),
  })
  const meta = (
    <span className="mt-1 block text-[13px] text-muted">
      {ROLE_LABELS[s.role]} · {humanize(s.category)} · {humanize(s.difficulty)} ·{' '}
      {s.executionCapability === 'CODE' && s.language ? LANGUAGE_LABELS[s.language] : 'Approach'}
    </span>
  )
  return (
    <li className="grid grid-cols-[2rem_1fr_auto] items-start gap-3 py-4">
      <span className="font-display text-xl text-muted">{s.position}</span>
      {s.submitted ? (
        <span>
          <span className="text-ink">{s.title}</span>
          {meta}
        </span>
      ) : (
        <Link to={`/scenario-lab/${lab.id}/scenarios/${s.id}`} className="group">
          <span className="text-ink underline-offset-4 group-hover:underline">{s.title}</span>
          {meta}
        </Link>
      )}
      <span className="pt-0.5 text-right text-[13px]">
        {!s.submitted && <span className="text-muted">Not submitted</span>}
        {s.evaluationStatus === 'PENDING' && <span className="text-muted">Evaluating…</span>}
        {s.evaluationStatus === 'COMPLETED' && (
          <Link to={`/scenario-lab/${lab.id}/scenarios/${s.id}/feedback`} className="text-ink underline underline-offset-4 hover:text-white">
            View feedback
          </Link>
        )}
        {s.evaluationStatus === 'FAILED' && (
          <span className="flex flex-col items-end gap-1">
            <span className="text-danger">Evaluation failed</span>
            <button type="button" onClick={() => retry.mutate()} disabled={retry.isPending} className="text-xs text-ink underline underline-offset-4">
              Try again
            </button>
          </span>
        )}
      </span>
    </li>
  )
}

function Finalizing({ lab }: { lab: ScenarioLab }) {
  const queryClient = useQueryClient()
  const retry = useMutation({
    mutationFn: () => retryFinalization(lab.id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['scenario-lab'] }),
  })
  return (
    <div className="border-t border-line pt-8">
      {lab.errorCode ? (
        <div className="space-y-4">
          <p role="alert" className="text-ink">
            {lab.errorMessage ?? 'Your assessment couldn’t be written.'} Every submission is saved.
          </p>
          <Button type="button" variant="secondary" busy={retry.isPending} onClick={() => retry.mutate()}>
            Try again
          </Button>
        </div>
      ) : (
        <p role="status" className="flex items-center gap-2.5 text-ink">
          <span aria-hidden className="size-1.5 animate-pulse rounded-full bg-ink motion-reduce:animate-none" />
          Preparing your assessment…
        </p>
      )}
    </div>
  )
}

/** Finish now with the submitted scenarios; the unsubmitted ones aren't assessed. Discarding stays possible. */
function FinishEarly({ lab, submitted }: { lab: ScenarioLab; submitted: number }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const finish = useMutation({
    mutationFn: () => finishLab(lab.id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['scenario-lab'] }),
  })
  const pending = lab.scenarios.some((s) => s.evaluationStatus === 'PENDING')
  const failed = lab.scenarios.some((s) => s.evaluationStatus === 'FAILED')
  const blocked = pending || failed
  const remaining = lab.scenarios.length - submitted
  return (
    <div className="mt-8 space-y-3">
      {!confirming ? (
        <div className="flex flex-wrap items-center gap-4">
          {remaining > 0 && (
            <Button type="button" variant="secondary" className="h-8" disabled={blocked} onClick={() => setConfirming(true)}>
              Finish lab now
            </Button>
          )}
          {blocked && (
            <span className="text-xs text-muted">
              {pending ? 'Wait for your answers to be evaluated before finishing.' : 'Try the failed evaluation again before finishing.'}
            </span>
          )}
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-3 rounded-md border border-line px-3 py-2" role="group" aria-label="Confirm finishing the lab">
          <span className="text-sm text-ink">
            Finish with {submitted} of {lab.scenarios.length} submitted? The {remaining} unsubmitted {remaining === 1 ? 'scenario' : 'scenarios'} won’t be
            assessed.
          </span>
          <Button type="button" className="h-8" busy={finish.isPending} onClick={() => finish.mutate()}>
            Finish lab
          </Button>
          <Button type="button" variant="ghost" className="h-8" onClick={() => setConfirming(false)}>
            Keep going
          </Button>
        </div>
      )}
      <ErrorBanner error={finish.error} />
      <EndLab lab={lab} label="Discard this lab instead" warning={`Your ${submitted} submitted ${submitted === 1 ? 'answer' : 'answers'} won’t be saved to history.`} />
    </div>
  )
}

function EndLab({ lab, label, warning }: { lab: ScenarioLab; label: string; warning?: string }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const end = useMutation({
    mutationFn: () => cancelLab(lab.id),
    onSuccess: () => {
      rememberLab(null)
      void queryClient.invalidateQueries({ queryKey: ['scenario-lab'] })
    },
  })
  if (!confirming) {
    return (
      <button type="button" onClick={() => setConfirming(true)} className="mt-8 text-xs text-muted underline underline-offset-4 hover:text-ink">
        {label}
      </button>
    )
  }
  return (
    <div className="mt-8 flex flex-wrap items-center gap-3" role="group" aria-label="Confirm ending the lab">
      <span className="text-sm text-ink">Discard this lab? {warning ?? 'Nothing from it will be saved to history.'}</span>
      <Button type="button" variant="secondary" className="h-8" busy={end.isPending} onClick={() => end.mutate()}>
        Discard lab
      </Button>
      <Button type="button" variant="ghost" className="h-8" onClick={() => setConfirming(false)}>
        Keep going
      </Button>
      {end.error && <p className="w-full text-sm text-danger">{end.error instanceof ApiError ? end.error.message : 'Please try again.'}</p>}
    </div>
  )
}
