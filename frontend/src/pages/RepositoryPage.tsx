import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ApiError, getRepository, type ImportedRepository, type LabelCount } from '../api'
import { useImportRepository } from '../repositories/useImportRepository'
import { Button } from '../ui'

const number = new Intl.NumberFormat('en-US')

/** Overview of one imported repository: what was imported, what was skipped, and what comes next. */
export function RepositoryPage() {
  const { id = '' } = useParams()
  const repo = useQuery({
    queryKey: ['repository', id],
    queryFn: () => getRepository(id),
    // A 4xx (not found, not yours) won't change on retry; only retry network/server hiccups.
    retry: (failures, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failures < 2,
    // An import running in another tab finishes on its own; check back until it does.
    refetchInterval: (query) => (query.state.data?.status === 'IMPORTING' ? 2000 : false),
  })

  return (
    <div className="mx-auto w-full max-w-3xl px-4 py-10 md:py-16">
      {repo.isPending && <p className="text-sm text-muted">Loading repository…</p>}
      {repo.error && <NotFound error={repo.error} />}
      {repo.data && <Overview repo={repo.data} />}
    </div>
  )
}

function NotFound({ error }: { error: unknown }) {
  return (
    <div role="alert">
      <h1 className="font-display text-3xl text-ink">
        {error instanceof ApiError ? error.message : 'Something went wrong. Please try again.'}
      </h1>
      <Link to="/dashboard" className="mt-4 inline-block text-sm text-muted underline underline-offset-4 hover:text-ink">
        Back to the dashboard
      </Link>
    </div>
  )
}

function Overview({ repo }: { repo: ImportedRepository }) {
  return (
    <article className="animate-fade-in motion-reduce:animate-none">
      <a
        href={repo.url}
        target="_blank"
        rel="noreferrer"
        className="font-mono text-xs text-muted transition-colors hover:text-ink"
      >
        {repo.owner} / {repo.name} ↗
      </a>
      <h1 className="mt-3 break-words font-display text-[2.75rem] leading-[1.05] tracking-[-0.015em] text-ink sm:text-6xl">{repo.name}</h1>
      {repo.description && <p className="mt-4 max-w-2xl text-[15px] leading-relaxed text-muted">{repo.description}</p>}
      <StatusLine repo={repo} />

      <dl className="mt-10 grid grid-cols-2 gap-x-6 gap-y-5 border-y border-line py-6 sm:grid-cols-5">
        <Fact label="Language">{repo.primaryLanguage ?? 'Not detected'}</Fact>
        <Fact label="Visibility">{repo.visibility === 'PRIVATE' ? 'Private' : 'Public'}</Fact>
        <Fact label="Default branch">
          <span className="font-mono text-[13px]">{repo.defaultBranch}</span>
        </Fact>
        <Fact label="Stars">{number.format(repo.stars)}</Fact>
        <Fact label="Forks">{number.format(repo.forks)}</Fact>
      </dl>

      {repo.status === 'READY' && <Inventory repo={repo} />}

      <section className="mt-12 flex flex-wrap items-center gap-x-5 gap-y-3 border-t border-line pt-8">
        <Button type="button" disabled title="Analysis is the next feature being built">
          Run review
        </Button>
        <p className="text-sm text-muted">Analysis comes next. Nothing has been reviewed yet.</p>
      </section>
    </article>
  )
}

function StatusLine({ repo }: { repo: ImportedRepository }) {
  const retry = useImportRepository()
  if (repo.status === 'IMPORTING') {
    return <Status dot="bg-muted animate-pulse motion-reduce:animate-none">Importing repository…</Status>
  }
  if (repo.status === 'FAILED') {
    return (
      <div className="mt-6 space-y-3">
        <Status dot="bg-danger">{repo.failureReason ?? 'The import failed.'}</Status>
        {retry.error && (
          <p role="alert" className="text-sm text-danger">
            {retry.error instanceof ApiError ? retry.error.message : 'Something went wrong. Please try again.'}
          </p>
        )}
        <Button type="button" variant="secondary" busy={retry.isPending} onClick={() => retry.mutate(repo.url)}>
          Try importing again
        </Button>
      </div>
    )
  }
  return <Status dot="bg-white">Imported and ready for analysis.</Status>
}

function Status({ dot, children }: { dot: string; children: ReactNode }) {
  return (
    <p role="status" className="mt-6 flex items-center gap-2.5 text-sm text-ink">
      <span aria-hidden className={`size-1.5 shrink-0 rounded-full ${dot}`} />
      {children}
    </p>
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

function Inventory({ repo }: { repo: ImportedRepository }) {
  return (
    <section className="mt-12" aria-labelledby="inventory">
      <h2 id="inventory" className="font-display text-2xl text-ink">
        File inventory
      </h2>
      <p className="mt-1 text-sm text-muted">Paths and sizes only. No file contents have been read yet.</p>

      <dl className="mt-6 grid grid-cols-3 gap-6">
        <Total label="Files">{repo.fileCount}</Total>
        <Total label="Relevant">{repo.relevantFileCount}</Total>
        <Total label="Ignored">{repo.ignoredFileCount}</Total>
      </dl>

      <div className="mt-10 grid gap-10 sm:grid-cols-2">
        <Ledger title="Relevant files by language" rows={repo.languages} empty="No recognised source languages." />
        <Ledger title="Ignored, and why" rows={repo.ignoredReasons} empty="Nothing was ignored." />
      </div>
    </section>
  )
}

function Total({ label, children }: { label: string; children: number }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-1 text-4xl tabular-nums tracking-[-0.02em] text-ink sm:text-5xl">{number.format(children)}</dd>
    </div>
  )
}

const LEDGER_ROWS = 8

function Ledger({ title, rows, empty }: { title: string; rows: LabelCount[]; empty: string }) {
  const shown = rows.slice(0, LEDGER_ROWS)
  const rest = rows.slice(LEDGER_ROWS).reduce((sum, r) => sum + r.count, 0)
  return (
    <div>
      <h3 className="mb-2 text-xs text-muted">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-sm text-muted">{empty}</p>
      ) : (
        <ul className="divide-y divide-line border-y border-line">
          {shown.map((r) => (
            <li key={r.label} className="flex items-baseline justify-between gap-4 py-2 text-sm">
              <span className="text-ink">{r.label}</span>
              <span className="font-mono text-xs tabular-nums text-muted">{number.format(r.count)}</span>
            </li>
          ))}
          {rest > 0 && (
            <li className="flex items-baseline justify-between gap-4 py-2 text-sm">
              <span className="text-muted">Other</span>
              <span className="font-mono text-xs tabular-nums text-muted">{number.format(rest)}</span>
            </li>
          )}
        </ul>
      )}
    </div>
  )
}
