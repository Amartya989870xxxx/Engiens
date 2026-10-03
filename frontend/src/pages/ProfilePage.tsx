import { useState, type FormEvent, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type GitHubConnection, type Profile } from '../api'
import { Button, ErrorBanner } from '../ui'
import { initialDraft, saveBeforeLeaving, stepError, toRequest, type Draft } from '../profile/draft'
import { describeLevel } from '../profile/options'
import { useGitHubLookup, useKeepDraftOnExpiry, useRestoredDraft, useSaveProfile } from '../profile/profileHooks'
import { AboutStep, ExperienceStep, GoalsStep, StackStep } from '../profile/steps'
import { returnErrorMessage, useGitHubAccess } from '../profile/useGitHubAccess'

/** Each section edits the same fields as the matching onboarding step (same index). */
const ABOUT = 0
const STACK = 1
const EXPERIENCE = 2
const GOAL = 3

export function ProfilePage() {
  const profile = useQuery({ queryKey: ['profile'], queryFn: () => api<Profile>('/api/profile') })
  return (
    <div className="mx-auto w-full max-w-2xl px-4 py-10 md:py-16">
      <h1 className="font-display text-4xl tracking-[-0.01em] text-ink">Profile</h1>
      <p className="mt-2 text-sm text-muted">This tailors your feedback to where you are now. It isn’t a measure of your ability.</p>
      <div className="mt-8">
        {profile.isPending && <p className="text-sm text-muted">Loading your profile…</p>}
        <ErrorBanner error={profile.error} />
        {profile.data && <ProfileSections profile={profile.data} />}
      </div>
    </div>
  )
}

function ProfileSections({ profile }: { profile: Profile }) {
  const { githubOutcome, saved } = useRestoredDraft()
  // Coming back from GitHub (or after re-login) reopens the section that was being edited.
  const [editing, setEditing] = useState<number | null>(() => (githubOutcome ? EXPERIENCE : (saved?.step ?? null)))
  const [draft, setDraft] = useState<Draft>(() => {
    const start = saved?.draft ?? initialDraft(profile, '')
    return githubOutcome ? { ...start, githubAccess: 'private' } : start
  })
  const [error, setError] = useState<string | null>(null)

  const access = useGitHubAccess(() => saveBeforeLeaving(draft, EXPERIENCE), returnErrorMessage(githubOutcome))
  const connection = access.connection
  const githubLogin = connection?.connected ? connection.login : null
  const { lookup, verify, checking } = useGitHubLookup(draft.githubUrl.trim())
  const save = useSaveProfile(() => setEditing(null))
  useKeepDraftOnExpiry(draft, editing)

  function startEditing(section: number) {
    // Always start from what's saved, so a cancelled edit leaves nothing behind.
    setDraft({ ...initialDraft(profile, ''), githubAccess: githubLogin ? 'private' : 'public' })
    setError(null)
    save.reset()
    setEditing(section)
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (editing === null) return
    const problem = stepError(editing, draft, githubLogin !== null)
    if (problem) return setError(problem)
    if (editing === EXPERIENCE && !(await verify(draft))) return
    setError(null)
    save.mutate(toRequest(draft, githubLogin))
  }

  const section = (index: number, title: string, view: ReactNode, form: ReactNode) => (
    <Section
      title={title}
      editing={editing === index}
      locked={editing !== null && editing !== index}
      onEdit={() => startEditing(index)}
      onCancel={() => setEditing(null)}
      onSubmit={submit}
      saving={save.isPending || checking}
      error={error}
      saveError={save.error}
      view={view}
      form={form}
    />
  )

  return (
    <div className="space-y-4">
      {section(
        ABOUT,
        'About you',
        <Fields>
          <FieldRow label="Name">{profile.name}</FieldRow>
          <FieldRow label="Status">{describeLevel(profile.level, profile.classYear, profile.workExperience)}</FieldRow>
        </Fields>,
        <AboutStep draft={draft} update={setDraft} />,
      )}
      {section(
        STACK,
        'Stack',
        <Fields>
          <FieldRow label="Languages"><Tags values={profile.languages} /></FieldRow>
          <FieldRow label="Frameworks and tools"><Tags values={profile.frameworks} /></FieldRow>
          <FieldRow label="Databases"><Tags values={profile.databases} /></FieldRow>
        </Fields>,
        <StackStep draft={draft} update={setDraft} />,
      )}
      {section(
        EXPERIENCE,
        'Experience and GitHub',
        <Fields>
          <FieldRow label="Experienced in"><Tags values={profile.experienceAreas} /></FieldRow>
          <FieldRow label="GitHub"><GitHubSummary profile={profile} connection={connection} /></FieldRow>
        </Fields>,
        <ExperienceStep draft={draft} update={setDraft} lookup={lookup} access={access} />,
      )}
      {section(
        GOAL,
        'Goal',
        <Fields>
          <FieldRow label="Working toward">{profile.goals ?? <Empty />}</FieldRow>
        </Fields>,
        <GoalsStep draft={draft} update={setDraft} />,
      )}
    </div>
  )
}

type SectionProps = {
  title: string
  editing: boolean
  /** Another section is being edited; only one at a time keeps saves simple and unambiguous. */
  locked: boolean
  onEdit: () => void
  onCancel: () => void
  onSubmit: (e: FormEvent) => void
  saving: boolean
  error: string | null
  saveError: unknown
  view: ReactNode
  form: ReactNode
}

function Section({ title, editing, locked, onEdit, onCancel, onSubmit, saving, error, saveError, view, form }: SectionProps) {
  return (
    <section className={`rounded-xl border bg-surface p-6 transition-colors ${editing ? 'border-line-strong' : 'border-line'}`}>
      <div className="mb-5 flex items-center justify-between">
        <h2 className="font-display text-xl text-ink">{title}</h2>
        {!editing && (
          <Button type="button" variant="secondary" onClick={onEdit} disabled={locked} aria-label={`Edit ${title}`}>
            Edit
          </Button>
        )}
      </div>
      {editing ? (
        <form onSubmit={onSubmit} noValidate className="animate-fade-in motion-reduce:animate-none">
          <div className="space-y-5">{form}</div>
          {error && (
            <p role="alert" className="mt-5 text-sm text-danger">
              {error}
            </p>
          )}
          <div className="mt-5">
            <ErrorBanner error={saveError} />
          </div>
          <div className="mt-6 flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            <Button type="submit" busy={saving}>
              Save changes
            </Button>
          </div>
        </form>
      ) : (
        view
      )}
    </section>
  )
}

function Fields({ children }: { children: ReactNode }) {
  return <dl className="space-y-4 text-sm">{children}</dl>
}

function FieldRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 sm:grid-cols-[11rem_1fr] sm:gap-4">
      <dt className="text-muted">{label}</dt>
      <dd className="text-ink">{children}</dd>
    </div>
  )
}

function Tags({ values }: { values: string[] }) {
  if (values.length === 0) return <Empty />
  return (
    <span className="flex flex-wrap gap-1.5">
      {values.map((v) => (
        <span key={v} className="rounded bg-raised px-2 py-0.5 text-[13px]">
          {v}
        </span>
      ))}
    </span>
  )
}

function GitHubSummary({ profile, connection }: { profile: Profile; connection: GitHubConnection | undefined }) {
  if (connection?.connected) {
    return (
      <>
        Connected as <span className="font-mono text-[13px]">@{connection.login}</span>
        <span className="text-muted"> · including private repositories you chose</span>
      </>
    )
  }
  if (profile.githubUsername) {
    return (
      <>
        <a href={`https://github.com/${profile.githubUsername}`} target="_blank" rel="noreferrer" className="underline underline-offset-4">
          @{profile.githubUsername}
        </a>
        <span className="text-muted"> · public repositories</span>
      </>
    )
  }
  return <Empty />
}

const Empty = () => <span className="text-muted">Not added</span>
