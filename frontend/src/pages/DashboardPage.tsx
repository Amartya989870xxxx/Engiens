import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, type Profile } from '../api'
import { describeLevel } from '../profile/options'
import { useYourRepos } from '../dashboard/useYourRepos'
import { ArrowUpIcon, FlaskIcon, RepoIcon, UserIcon } from '../shell/icons'
import { ErrorBanner } from '../ui'

export function DashboardPage() {
  const profile = useQuery({ queryKey: ['profile'], queryFn: () => api<Profile>('/api/profile') })
  const { repos, loading, hasGitHub } = useYourRepos(profile.data)
  const [url, setUrl] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const location = useLocation()

  // Focus on arrival, and again whenever "New review" is clicked (each click is a new location.key).
  useEffect(() => inputRef.current?.focus(), [location.key])

  function pick(htmlUrl: string) {
    setUrl(htmlUrl)
    setNotice(null)
    inputRef.current?.focus()
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!url.trim()) return
    // Repository import is the next feature in the build plan; say so instead of pretending.
    setNotice('Repository analysis is the next feature being built. Once it’s ready, this will start your review.')
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
                setNotice(null)
              }}
              placeholder="Paste a GitHub repository link"
              className="min-w-0 flex-1 bg-transparent py-2 text-[15px] text-ink placeholder:text-muted focus:outline-none"
            />
            <button
              type="submit"
              disabled={!url.trim()}
              aria-label="Start review"
              className="grid size-10 shrink-0 place-items-center rounded-full bg-white text-black transition-opacity hover:opacity-85 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white disabled:opacity-30"
            >
              <ArrowUpIcon />
            </button>
          </div>
        </form>

        {notice && (
          <p role="status" className="mt-3 px-5 text-sm text-muted animate-fade-in motion-reduce:animate-none">
            {notice}
          </p>
        )}
        <ErrorBanner error={profile.error} />
        {profile.data && <TunedFor profile={profile.data} />}

        <ul className="mt-8 space-y-1">
          {repos?.map((r) => (
            <Suggestion key={r.htmlUrl} icon={<RepoIcon />} onClick={() => pick(r.htmlUrl)} meta={r.privateRepo ? 'Private' : (r.language ?? undefined)}>
              Review <span className="text-ink">{r.name}</span>
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
}

function Suggestion({ icon, children, meta, onClick, to, soon }: SuggestionProps) {
  const body = (
    <>
      <span className="text-muted">{icon}</span>
      <span className="flex-1 truncate">{children}</span>
      {meta && <span className="font-mono text-xs text-muted">{meta}</span>}
      {soon && <span className="rounded border border-line px-1.5 py-px text-[10px] uppercase tracking-wider">Soon</span>}
    </>
  )
  const base = 'flex w-full items-center gap-4 rounded-lg px-4 py-2.5 text-left text-[15px]'
  const interactive = `${base} text-muted transition-colors hover:bg-surface hover:text-ink focus-visible:outline-2 focus-visible:outline-white`
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
        <button type="button" onClick={onClick} className={interactive}>
          {body}
        </button>
      )}
    </li>
  )
}
