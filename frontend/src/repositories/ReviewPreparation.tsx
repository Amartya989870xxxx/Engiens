import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, getLatestAnalysis, prepareRepository, type AnalysisRun } from '../api'
import { Button } from '../ui'

const number = new Intl.NumberFormat('en-US')
const kb = (bytes: number | null) => (bytes == null ? '—' : `${number.format(Math.round(bytes / 1024))} KB`)

/**
 * "Prepare for review": profile the project, collect deterministic evidence, choose the files a
 * reviewer should read. Shows a short summary only; the evidence itself is for the review step.
 */
export function ReviewPreparation({ repositoryId }: { repositoryId: string }) {
  const qc = useQueryClient()
  const latest = useQuery({ queryKey: ['analysis-latest', repositoryId], queryFn: () => getLatestAnalysis(repositoryId) })
  const prepare = useMutation({
    mutationFn: () => prepareRepository(repositoryId),
    onSuccess: (run) => qc.setQueryData(['analysis-latest', repositoryId], run),
    // A failed run is recorded by the server; reload it so its reason shows.
    onError: () => qc.invalidateQueries({ queryKey: ['analysis-latest', repositoryId] }),
  })

  const run = latest.data
  return (
    <section className="mt-12 border-t border-line pt-8" aria-labelledby="preparation">
      <h2 id="preparation" className="font-display text-2xl text-ink">
        Review preparation
      </h2>
      <p className="mt-1 max-w-2xl text-sm text-muted">
        Engiens profiles the project, collects evidence it can check for itself, and picks the files a reviewer should
        read. No AI is involved in this step.
      </p>

      <div className="mt-6">
        {latest.isPending && <p className="text-sm text-muted">Loading…</p>}
        {prepare.isPending && (
          <p role="status" className="text-sm text-muted">
            Preparing: profiling the project and selecting files…
          </p>
        )}
        {prepare.error && !prepare.isPending && (
          <p role="alert" className="mb-4 text-sm text-danger">
            {prepare.error instanceof ApiError ? prepare.error.message : 'Something went wrong. Please try again.'}
          </p>
        )}
        {!latest.isPending && !prepare.isPending && (run && run.status === 'COMPLETED' ? (
          <Summary run={run} />
        ) : (
          <div className="space-y-3">
            {run?.status === 'FAILED' && !prepare.error && (
              <p role="alert" className="text-sm text-danger">
                {run.failureReason ?? 'The last preparation failed.'}
              </p>
            )}
            <Button type="button" variant={run ? 'secondary' : 'primary'} onClick={() => prepare.mutate()}>
              {run ? 'Try again' : 'Prepare for review'}
            </Button>
          </div>
        ))}
      </div>
    </section>
  )
}

function Summary({ run }: { run: AnalysisRun }) {
  const p = run.profile
  const stack = [...(p?.frameworks ?? []), ...(p?.databases ?? [])].map((d) => d.name)
  const languages = (p?.languages ?? []).slice(0, 3).map((l) => l.name)
  return (
    <div className="animate-fade-in motion-reduce:animate-none">
      <p role="status" className="flex items-center gap-2.5 text-sm text-ink">
        <span aria-hidden className="size-1.5 shrink-0 rounded-full bg-white" />
        Prepared for review{run.commitSha && <> from commit <span className="font-mono text-[13px]">{run.commitSha.slice(0, 7)}</span></>}.
      </p>
      <dl className="mt-5 grid grid-cols-2 gap-x-6 gap-y-5 sm:grid-cols-4">
        <Fact label="Detected stack">{stack.length ? stack.join(', ') : languages.join(', ') || 'Not detected'}</Fact>
        <Fact label="Signals collected">{number.format(run.stats.signalCount ?? 0)}</Fact>
        <Fact label="Files for the reviewer">
          {number.format(run.stats.contextFileCount ?? 0)} · {kb(run.stats.contextBytes)}
        </Fact>
        <Fact label="Files downloaded">{number.format(run.stats.filesFetched ?? 0)}</Fact>
      </dl>
    </div>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-1 text-sm tabular-nums text-ink">{children}</dd>
    </div>
  )
}
