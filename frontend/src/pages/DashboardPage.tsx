import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError, type Profile } from '../api'
import { useImportRepository } from '../repositories/useImportRepository'
import { describeLevel } from '../profile/options'
import { useYourRepos } from '../dashboard/useYourRepos'
import { ArrowUpIcon, FlaskIcon, RepoIcon, UserIcon } from '../shell/icons'
import { ErrorBanner } from '../ui'

export function DashboardPage() {
  const profile = useQuery({ queryKey: ['profile'], queryFn: () => api<Profile>('/api/profile') })
  const { repos, loading, hasGitHub } = useYourRepos(profile.data)
  const [url, setUrl] = useState('')
  const importRepo = useImportRepository()
  const inputRef = useRef<HTMLInputElement>(null)
  const location = useLocation()

  // Focus on arrival, and again whenever "New review" is clicked (each click is a new location.key).
  useEffect(() => inputRef.current?.focus(), [location.key])

  // Which link is being imported right now (a row's URL or the typed one), if any.
  const importing = importRepo.isPending ? importRepo.variables : null

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!url.trim() || importing) return
    importRepo.mutate(url.trim())
  }

  return (
    <div className="flex min-h-[calc(100vh-57px)] flex-col items-center justify-center px-4 py-12 md:min-h-screen">
      <div className="w-full max-w-2xl">
        <h1 className="text-balance text-center font-display text-[2.25rem] leading-tight tracking-[-0.01em] text-ink sm:text-[2.6rem]">
          Which repository should we review?
        </h1>

        <form onSubmit={submit} className="mt-10">
          <label htmlFor="repo-url" className="sr-only">
            GitHub repository URL
          </label>
          <div className="flex items-center gap-3 rounded-full border border-line bg-surface py-2 pl-5 pr-2 transition-colors focus-within:border-line-strong">
            <span className="text-muted">
              <RepoIcon />
            </span>
            <input
              ref={inputRef}
              id="repo-url"
              type="url"
              inputMode="url"
              value={url}
              onChange={(e) => {
                setUrl(e.target.value)
                importRepo.reset()
              }}
              placeholder="Paste a GitHub repository link"
              className="min-w-0 flex-1 bg-transparent py-2 text-[15px] text-ink placeholder:text-muted focus:outline-none"
            />
            <button
              type="submit"
              disabled={!url.trim() || importing !== null}
              aria-label={importing === url.trim() && importing ? 'Importing repository' : 'Import repository'}
              className="grid size-10 shrink-0 place-items-center rounded-full bg-white text-black transition-opacity hover:opacity-85 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white disabled:opacity-30"
            >
              <ArrowUpIcon />
            </button>
          </div>
        </form>

        {importing && importing === url.trim() && (
          <p role="status" className="mt-3 px-5 text-sm text-muted animate-fade-in motion-reduce:animate-none">
            Importing repository…
          </p>
        )}
        {importRepo.error && (
          <p role="alert" className="mt-3 px-5 text-sm text-danger animate-fade-in motion-reduce:animate-none">
            {importRepo.error instanceof ApiError ? importRepo.error.message : 'Something went wrong. Please try again.'}
          </p>
        )}
        <ErrorBanner error={profile.error} />
        {profile.data && <TunedFor profile={profile.data} />}

        <ul className="mt-8 space-y-1">
          {repos?.map((r) => (
            <Suggestion
              key={r.htmlUrl}
              icon={<RepoIcon />}
              onClick={() => importRepo.mutate(r.htmlUrl)}
              disabled={importing !== null}
              meta={r.privateRepo ? 'Private' : (r.language ?? undefined)}
            >
              {importing === r.htmlUrl ? 'Importing' : 'Review'} <span className="text-ink">{r.name}</span>
              {importing === r.htmlUrl && '…'}
            </Suggestion>
          ))}
          {loading && <li className="px-4 py-2.5 text-sm text-muted">Loading your repositories…</li>}
          {!hasGitHub && profile.data && (
            <Suggestion icon={<RepoIcon />} to="/profile">
              Add your GitHub profile to pick from your projects
            </Suggestion>
          )}
          <Suggestion icon={<FlaskIcon />} soon>
            Practice a production incident
          </Suggestion>
          <Suggestion icon={<UserIcon />} to="/profile">
            Update your engineering profile
          </Suggestion>
        </ul>
      </div>
    </div>
  )
}

/** Shows the user that feedback is personalised, and what it's based on. */
function TunedFor({ profile }: { profile: Profile }) {
  const stack = profile.languages.slice(0, 3).join(', ')
  return (
    <p className="mt-3 px-5 text-xs text-muted">
      Feedback tuned for {describeLevel(profile.level, profile.classYear, profile.workExperience).toLowerCase()}
      {stack && ` · ${stack}`}
      {' · '}
      <Link to="/profile" className="underline underline-offset-2 hover:text-ink">
        Edit
      </Link>
    </p>
  )
}

type SuggestionProps = {
  icon: ReactNode
  children: ReactNode
  meta?: string
  onClick?: () => void
  to?: string
  soon?: boolean
  /** While one import runs, the other rows can't start a second one. */
  disabled?: boolean
}

function Suggestion({ icon, children, meta, onClick, to, soon, disabled }: SuggestionProps) {
  const body = (
    <>
      <span className="text-muted">{icon}</span>
      <span className="flex-1 truncate">{children}</span>
      {meta && <span className="font-mono text-xs text-muted">{meta}</span>}
      {soon && <span className="rounded border border-line px-1.5 py-px text-[10px] uppercase tracking-wider">Soon</span>}
    </>
  )
  const base = 'flex w-full items-center gap-4 rounded-lg px-4 py-2.5 text-left text-[15px]'
  const interactive = `${base} text-muted transition-colors hover:bg-surface hover:text-ink focus-visible:outline-2 focus-visible:outline-white disabled:cursor-wait disabled:hover:bg-transparent disabled:hover:text-muted`
  return (
    <li>
      {soon ? (
        <span aria-disabled className={`${base} text-muted/60`}>
          {body}
        </span>
      ) : to ? (
        <Link to={to} className={interactive}>
          {body}
        </Link>
      ) : (
        <button type="button" onClick={onClick} disabled={disabled} className={interactive}>
          {body}
        </button>
      )}
    </li>
  )
}
