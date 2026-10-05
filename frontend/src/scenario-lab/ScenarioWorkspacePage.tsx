import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  getScenario,
  runScenario,
  saveDraft,
  submitScenario,
  type FileContent,
  type ScenarioDetail,
  type WorkMode,
} from '../api'
import { Button, ErrorBanner } from '../ui'
import { CodeEditor } from './CodeEditor'
import { humanize, LANGUAGE_LABELS, ROLE_LABELS, SENIORITY_LABELS } from './options'
import { RunResults } from './RunResults'
import { ScenarioProblem } from './ScenarioProblem'

const AUTOSAVE_MS = 1000

/** One scenario of the open lab: read the problem, work in code (or explain the approach), Run, Submit. */
export function ScenarioWorkspacePage() {
  const { labId = '', scenarioId = '' } = useParams()
  const scenario = useQuery({
    queryKey: ['scenario', labId, scenarioId],
    queryFn: () => getScenario(labId, scenarioId),
    staleTime: Infinity, // the editor owns the text once loaded; refetching would overwrite typing
    retry: false,
  })

  if (scenario.isPending) return <p className="px-6 py-10 text-sm text-muted">Loading scenario…</p>
  if (scenario.error) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-16" role="alert">
        <h1 className="font-display text-3xl text-ink">
          {scenario.error instanceof ApiError ? scenario.error.message : 'Something went wrong. Please try again.'}
        </h1>
        <Link to="/scenario-lab" className="mt-4 inline-block text-sm text-muted underline underline-offset-4 hover:text-ink">
          Back to Scenario Lab
        </Link>
      </div>
    )
  }
  return <Workspace detail={scenario.data} />
}

function Workspace({ detail }: { detail: ScenarioDetail }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const canCode = detail.executionCapability === 'CODE'
  const editable = detail.files.filter((f) => f.editable)

  // Both drafts live here, so switching modes or files never loses either.
  const [mode, setMode] = useState<WorkMode>(canCode ? detail.draftMode : 'APPROACH')
  const [files, setFiles] = useState<Record<string, string>>(() => Object.fromEntries(detail.files.map((f) => [f.path, f.content])))
  const [approach, setApproach] = useState(detail.draftApproach ?? '')
  const [activePath, setActivePath] = useState(editable[0]?.path ?? detail.files[0]?.path)
  const [confirming, setConfirming] = useState(false)

  const editedFiles = useCallback(
    (): FileContent[] => editable.map((f) => ({ path: f.path, content: files[f.path] ?? f.content })),
    [editable, files],
  )

  // ---- autosave: a second after the last change, and immediately on a mode switch ------------------
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const dirty = useRef(false)
  const latest = useRef({ mode, files: editedFiles(), approach })
  useEffect(() => {
    latest.current = { mode, files: editedFiles(), approach }
  })
  const persist = useCallback(async () => {
    if (!dirty.current) return
    dirty.current = false
    setSaveState('saving')
    const { mode: m, files: f, approach: a } = latest.current
    try {
      await saveDraft(detail.labId, detail.id, { mode: m, files: canCode ? f : undefined, approach: a })
      setSaveState('saved')
    } catch {
      dirty.current = true
      setSaveState('error')
    }
  }, [canCode, detail.id, detail.labId])

  useEffect(() => {
    if (!dirty.current) return
    const timer = setTimeout(persist, AUTOSAVE_MS)
    return () => clearTimeout(timer)
  }, [files, approach, persist])
  useEffect(() => () => void persist(), [persist]) // leaving the page keeps the last edits

  const change = (update: () => void) => {
    dirty.current = true
    update()
  }
  const switchMode = (next: WorkMode) => {
    dirty.current = true
    setMode(next)
    latest.current = { ...latest.current, mode: next }
    void persist()
  }

  // ---- run and submit ------------------------------------------------------------------------------
  const run = useMutation({ mutationFn: () => runScenario(detail.labId, detail.id, editedFiles()) })
  const submit = useMutation({
    mutationFn: () =>
      submitScenario(detail.labId, detail.id, {
        mode,
        files: canCode ? editedFiles() : undefined,
        approach: approach.trim() ? approach : undefined,
      }),
    onSuccess: () => {
      dirty.current = false
      void queryClient.invalidateQueries({ queryKey: ['scenario-lab'] })
      navigate('/scenario-lab', { state: { submitted: detail.title } })
    },
  })

  if (detail.submitted) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-16">
        <p className="text-sm text-muted">Scenario {detail.position} of {detail.scenarioCount}</p>
        <h1 className="mt-2 font-display text-3xl text-ink">{detail.title}</h1>
        <p className="mt-4 text-sm text-muted">
          {detail.evaluationStatus === 'COMPLETED'
            ? 'You’ve submitted this scenario and its evaluation is ready.'
            : 'You’ve submitted this scenario. Its evaluation is on its way.'}
        </p>
        <div className="mt-6 flex gap-5 text-sm">
          {detail.evaluationStatus === 'COMPLETED' && (
            <Link to={`/scenario-lab/${detail.labId}/scenarios/${detail.id}/feedback`} className="text-ink underline underline-offset-4">
              View feedback
            </Link>
          )}
          <Link to="/scenario-lab" className="text-muted underline underline-offset-4 hover:text-ink">
            Back to the lab
          </Link>
        </div>
      </div>
    )
  }

  const active = detail.files.find((f) => f.path === activePath)
  return (
    <div className="px-4 py-8 md:px-8 md:py-10">
      <Link to="/scenario-lab" className="font-mono text-xs text-muted transition-colors hover:text-ink">
        ← Scenario Lab
      </Link>
      <header className="mt-5 max-w-4xl">
        <p className="text-xs uppercase tracking-[0.08em] text-muted">
          Scenario {detail.position} of {detail.scenarioCount}
          {detail.repositoryName && ` · ${detail.repositoryName}`}
        </p>
        <h1 className="mt-2 font-display text-3xl leading-tight text-ink md:text-4xl">{detail.title}</h1>
        <p className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-[13px] text-muted">
          <span>{ROLE_LABELS[detail.role]}</span>
          <span aria-hidden>·</span>
          <span>{SENIORITY_LABELS[detail.seniority]}</span>
          <span aria-hidden>·</span>
          <span>{humanize(detail.category)}</span>
          <span aria-hidden>·</span>
          <span>{humanize(detail.difficulty)}</span>
          {detail.language && (
            <>
              <span aria-hidden>·</span>
              <span>{LANGUAGE_LABELS[detail.language]}</span>
            </>
          )}
        </p>
      </header>

      <div className="mt-8 grid gap-10 xl:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]">
        <ScenarioProblem document={detail.document} />

        <section aria-label="Your work" className="min-w-0">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-display text-xl text-ink">{mode === 'CODE' ? 'Your code' : 'Explain your approach'}</h2>
            <div className="flex items-center gap-3">
              <SaveIndicator state={saveState} />
              {canCode && (
                <Button type="button" variant="ghost" className="h-8 px-2" onClick={() => switchMode(mode === 'CODE' ? 'APPROACH' : 'CODE')}>
                  {mode === 'CODE' ? 'Switch to Approach' : 'Switch to Code'}
                </Button>
              )}
            </div>
          </div>

          {mode === 'CODE' && active ? (
            <div className="overflow-hidden rounded-lg border border-line">
              <div role="tablist" aria-label="Files" className="flex overflow-x-auto border-b border-line bg-surface">
                {detail.files.map((f) => (
                  <button
                    key={f.path}
                    type="button"
                    role="tab"
                    aria-selected={f.path === activePath}
                    onClick={() => setActivePath(f.path)}
                    className={`whitespace-nowrap border-r border-line px-3 py-2 font-mono text-[12px] transition-colors ${
                      f.path === activePath ? 'bg-canvas text-ink' : 'text-muted hover:text-ink'
                    }`}
                  >
                    {f.path}
                    {!f.editable && <span className="ml-2 text-[10px] uppercase tracking-wider text-muted">read-only</span>}
                  </button>
                ))}
              </div>
              <div className="h-[28rem]">
                <CodeEditor
                  key={active.path}
                  label={`${active.path}${active.editable ? '' : ' (read-only)'}`}
                  lang={detail.language}
                  readOnly={!active.editable}
                  value={files[active.path] ?? active.content}
                  onChange={(text) => change(() => setFiles((prev) => ({ ...prev, [active.path]: text })))}
                />
              </div>
            </div>
          ) : (
            <div>
              <label htmlFor="approach" className="sr-only">
                Explain your approach
              </label>
              <textarea
                id="approach"
                value={approach}
                onChange={(e) => change(() => setApproach(e.target.value))}
                rows={16}
                maxLength={20000}
                placeholder="Diagnosis, root cause, the change you'd make, trade-offs, how you'd test it, and what happens at larger scale."
                className="w-full resize-y rounded-lg border border-line bg-canvas p-4 text-[15px] leading-relaxed text-ink placeholder:text-muted/60 focus:border-ink focus:outline-none"
              />
              {canCode && <p className="mt-2 text-xs text-muted">Your code is kept. Switch back any time.</p>}
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-center gap-3">
            {canCode && mode === 'CODE' && (
              <Button type="button" variant="secondary" busy={run.isPending} onClick={() => run.mutate()}>
                {run.isPending ? 'Running…' : 'Run checks'}
              </Button>
            )}
            {!confirming ? (
              <Button type="button" onClick={() => setConfirming(true)}>
                Submit
              </Button>
            ) : (
              <div className="flex flex-wrap items-center gap-3 rounded-md border border-line px-3 py-2" role="group" aria-label="Confirm submission">
                <span className="text-sm text-ink">Submit as your final answer? You can’t change it afterwards.</span>
                <Button type="button" className="h-8" busy={submit.isPending} onClick={() => submit.mutate()}>
                  Submit final answer
                </Button>
                <Button type="button" variant="ghost" className="h-8" onClick={() => setConfirming(false)}>
                  Keep working
                </Button>
              </div>
            )}
          </div>
          {submit.isPending && (
            <p className="mt-3 text-sm text-muted" role="status">
              Running the checks once more and saving your answer…
            </p>
          )}
          <div className="mt-3 space-y-3">
            <ErrorBanner error={submit.error} />
            <ErrorBanner error={run.error} />
          </div>

          {canCode && mode === 'CODE' && run.data && (
            <div className="mt-6 border-t border-line pt-5">
              <h3 className="mb-3 text-xs font-medium uppercase tracking-[0.08em] text-muted">Run results</h3>
              <RunResults result={run.data} />
            </div>
          )}
          {canCode && mode === 'CODE' && !run.data && (
            <p className="mt-5 text-xs leading-relaxed text-muted">
              Run checks your code against this scenario’s checks in an isolated sandbox. It’s feedback, not your grade: Submit
              sends your work for evaluation.
            </p>
          )}
        </section>
      </div>
    </div>
  )
}

function SaveIndicator({ state }: { state: 'idle' | 'saving' | 'saved' | 'error' }) {
  if (state === 'idle') return null
  const text = { saving: 'Saving…', saved: 'Saved', error: 'Couldn’t save. Retrying when you type.' }[state]
  return (
    <span role="status" className={`text-xs ${state === 'error' ? 'text-danger' : 'text-muted'}`}>
      {text}
    </span>
  )
}
