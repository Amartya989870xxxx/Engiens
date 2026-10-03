import type { GitHubConnection, GitHubProjects, GitHubRepo } from '../api'
import { Button, ErrorBanner, inputClass, labelClass } from '../ui'
import { ChoiceCard } from './ChoiceCard'
import type { Draft } from './draft'

export type PublicLookup = {
  data: GitHubProjects | undefined
  error: unknown
  isPending: boolean
  find: () => void
}

export type PrivateAccess = {
  connection: GitHubConnection | undefined
  repositories: GitHubRepo[] | undefined
  repositoriesError: unknown
  repositoriesLoading: boolean
  /** Message from GitHub's redirect back to us, if connecting failed. */
  returnError: string | null
  connect: () => void
  connecting: boolean
  connectError: unknown
  disconnect: () => void
  disconnecting: boolean
}

type Props = {
  draft: Draft
  update: (next: Draft) => void
  lookup: PublicLookup
  access: PrivateAccess
}

export function GitHubSection({ draft, update, lookup, access }: Props) {
  // Without a configured GitHub App, the server only supports public repositories.
  if (!access.connection?.available) {
    return <PublicRepos draft={draft} update={update} lookup={lookup} />
  }
  return (
    <fieldset className="space-y-2">
      <legend className={labelClass}>
        GitHub <span className="text-muted/60">(optional)</span>
      </legend>
      <ChoiceCard
        name="github-access"
        checked={draft.githubAccess === 'public'}
        onSelect={() => update({ ...draft, githubAccess: 'public' })}
        title="Public repositories only"
        description="Paste your profile link. No GitHub sign-in needed."
      />
      <ChoiceCard
        name="github-access"
        checked={draft.githubAccess === 'private'}
        onSelect={() => update({ ...draft, githubAccess: 'private' })}
        title="Include private repositories"
        description="You choose exactly which repositories to share on GitHub. Access is read-only."
      />
      <div className="pt-3">
        {draft.githubAccess === 'public' ? (
          <PublicRepos draft={draft} update={update} lookup={lookup} showLabel={false} />
        ) : (
          <PrivateRepos access={access} />
        )}
      </div>
    </fieldset>
  )
}

function PublicRepos({ draft, update, lookup, showLabel = true }: Omit<Props, 'access'> & { showLabel?: boolean }) {
  return (
    <div className="space-y-4 animate-fade-in motion-reduce:animate-none">
      <div>
        <label htmlFor="github-url" className={showLabel ? labelClass : 'sr-only'}>
          GitHub profile {showLabel && <span className="text-muted/60">(optional)</span>}
        </label>
        <div className="flex gap-2">
          <input
            id="github-url"
            type="url"
            inputMode="url"
            value={draft.githubUrl}
            onChange={(e) => update({ ...draft, githubUrl: e.target.value })}
            placeholder="https://github.com/your-name"
            maxLength={200}
            className={inputClass}
          />
          <Button type="button" variant="secondary" onClick={lookup.find} busy={lookup.isPending} disabled={!draft.githubUrl.trim()}>
            Find projects
          </Button>
        </div>
        <p className="mt-1.5 text-xs text-muted">We read your public repositories to learn what you've built.</p>
      </div>
      <ErrorBanner error={lookup.error} />
      {lookup.data && <RepoList repos={lookup.data.projects} owner={lookup.data.username} />}
    </div>
  )
}

function PrivateRepos({ access }: { access: PrivateAccess }) {
  const { connection } = access
  if (!connection?.connected) {
    return (
      <div className="space-y-3 animate-fade-in motion-reduce:animate-none">
        {access.returnError && (
          <p role="alert" className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
            {access.returnError}
          </p>
        )}
        <ErrorBanner error={access.connectError} />
        <Button type="button" onClick={access.connect} busy={access.connecting}>
          Connect GitHub
        </Button>
        <p className="text-xs text-muted">
          You'll go to GitHub, pick the repositories to share, and come straight back here. Your answers so far are kept.
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-3 animate-fade-in motion-reduce:animate-none">
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        <span className="text-ink">
          Connected as <span className="font-mono text-[13px]">@{connection.login}</span>
        </span>
        <span className="flex items-center gap-1">
          {connection.manageUrl && (
            <a
              href={connection.manageUrl}
              target="_blank"
              rel="noreferrer"
              className="rounded-md px-3 py-1.5 text-muted transition-colors hover:text-ink focus-visible:outline-2 focus-visible:outline-white"
            >
              Change repositories ↗
            </a>
          )}
          <Button type="button" variant="ghost" onClick={access.disconnect} busy={access.disconnecting}>
            Disconnect
          </Button>
        </span>
      </div>
      <ErrorBanner error={access.repositoriesError} />
      {access.repositoriesLoading && <p className="text-sm text-muted">Loading your repositories…</p>}
      {access.repositories && <RepoList repos={access.repositories} owner={connection.login ?? ''} shared />}
    </div>
  )
}

const SHOWN_REPOS = 5

function RepoList({ repos, owner, shared = false }: { repos: GitHubRepo[]; owner: string; shared?: boolean }) {
  if (repos.length === 0) {
    return (
      <p className="text-sm text-muted">
        {shared ? 'No repositories shared yet. Use “Change repositories” to pick some.' : `No public projects on @${owner} yet. You can continue without them.`}
      </p>
    )
  }
  const privateCount = repos.filter((r) => r.privateRepo).length
  const rest = repos.length - SHOWN_REPOS
  return (
    <div className="animate-fade-in rounded-md border border-line motion-reduce:animate-none">
      <p className="border-b border-line px-3 py-2 text-xs text-muted">
        {repos.length} {repos.length === 1 ? 'repository' : 'repositories'} {shared ? 'shared' : `on @${owner}`}
        {privateCount > 0 && ` · ${privateCount} private`}
      </p>
      <ul className="divide-y divide-line">
        {repos.slice(0, SHOWN_REPOS).map((r) => (
          <li key={r.htmlUrl} className="flex items-baseline justify-between gap-3 px-3 py-2 text-sm">
            <span className="flex min-w-0 items-baseline gap-2">
              <span className="truncate text-ink">{r.name}</span>
              {r.privateRepo && <span className="shrink-0 rounded border border-line px-1.5 text-[11px] text-muted">Private</span>}
            </span>
            <span className="shrink-0 font-mono text-xs text-muted">
              {r.language ?? '—'}
              {r.stars > 0 && ` · ★ ${r.stars}`}
            </span>
          </li>
        ))}
      </ul>
      {rest > 0 && <p className="border-t border-line px-3 py-2 text-xs text-muted">and {rest} more</p>}
    </div>
  )
}
