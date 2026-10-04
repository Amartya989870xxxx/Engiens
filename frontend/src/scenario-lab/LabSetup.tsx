import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { createLab, getRecentReviews, getReview, type ScenarioLab, type ScenarioRole, type Seniority } from '../api'
import { ChoiceCard } from '../profile/ChoiceCard'
import { TagSelect } from '../profile/TagSelect'
import { Button, ErrorBanner, fieldError, inputClass, labelClass } from '../ui'
import { COUNTS, ROLE_BY_LABEL, ROLE_LABELS, SENIORITY } from './options'

const ROLE_OPTIONS = Object.values(ROLE_LABELS)
const BROAD = ROLE_LABELS.BROAD_ENGINEERING

/**
 * Start a lab: the repository (from a review, or a GitHub link), the roles, the seniority and the size.
 * Roles and seniority are separate choices; the server validates every one of them again.
 */
export function LabSetup({ reviewId, onStarted }: { reviewId: string | null; onStarted: (lab: ScenarioLab) => void }) {
  const [source, setSource] = useState<{ reviewId: string } | { repositoryUrl: string }>(reviewId ? { reviewId } : { repositoryUrl: '' })
  const [roles, setRoles] = useState<string[]>([])
  const [seniority, setSeniority] = useState<Seniority>('SDE1')
  const [count, setCount] = useState<5 | 10 | 20>(5)
  const [touched, setTouched] = useState(false)

  const fromReview = 'reviewId' in source ? source.reviewId : null
  const review = useQuery({ queryKey: ['review', fromReview], queryFn: () => getReview(fromReview!), enabled: Boolean(fromReview) })
  const recent = useQuery({ queryKey: ['recent-reviews'], queryFn: getRecentReviews, enabled: !fromReview })

  const start = useMutation({
    mutationFn: () =>
      createLab({
        ...source,
        roles: roles.map((r) => ROLE_BY_LABEL[r]).filter(Boolean) as ScenarioRole[],
        seniority,
        scenarioCount: count,
      }),
    onSuccess: onStarted,
  })

  const roleError = touched && roles.length === 0 ? 'Choose at least one role' : fieldError(start.error, 'roles')
  const urlMissing = 'repositoryUrl' in source && !source.repositoryUrl.trim()

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (roles.length === 0 || urlMissing) return
    start.mutate()
  }

  // Broad Engineering stands alone: choosing it replaces specific roles, and vice versa.
  function changeRoles(next: string[]) {
    const added = next.find((r) => !roles.includes(r))
    if (added === BROAD) setRoles([BROAD])
    else setRoles(next.filter((r) => r !== BROAD))
  }

  return (
    <form onSubmit={submit} className="space-y-10" noValidate>
      <fieldset>
        <legend className="mb-3 font-display text-xl text-ink">Repository</legend>
        {fromReview ? (
          <div className="rounded-lg border border-line bg-surface p-4">
            {review.data ? (
              <>
                <p className="text-ink">{review.data.repositoryName}</p>
                <p className="mt-1 text-[13px] text-muted">
                  From your review
                  {review.data.commitSha && (
                    <>
                      {' '}
                      · commit <span className="font-mono text-ink/80">{review.data.commitSha.slice(0, 7)}</span>
                    </>
                  )}
                  . Scenarios use the same snapshot and the review’s findings.
                </p>
              </>
            ) : (
              <p className="text-sm text-muted">{review.error ? 'This review couldn’t be loaded.' : 'Loading review…'}</p>
            )}
            <button type="button" onClick={() => setSource({ repositoryUrl: '' })} className="mt-3 text-xs text-muted underline underline-offset-4 hover:text-ink">
              Use a different repository
            </button>
          </div>
        ) : (
          <div className="space-y-5">
            <label className="block">
              <span className={labelClass}>GitHub repository link</span>
              <input
                className={inputClass}
                value={'repositoryUrl' in source ? source.repositoryUrl : ''}
                onChange={(e) => setSource({ repositoryUrl: e.target.value })}
                placeholder="https://github.com/owner/repository"
                aria-invalid={touched && urlMissing ? true : undefined}
              />
              {touched && urlMissing && <span className="mt-1.5 block text-xs text-danger">Paste a GitHub repository link</span>}
            </label>
            {Array.isArray(recent.data) && recent.data.length > 0 && (
              <div>
                <p className="mb-2 text-xs text-muted">Or start from one of your reviews</p>
                <ul className="flex flex-wrap gap-2">
                  {recent.data.slice(0, 5).map((r) => (
                    <li key={r.id}>
                      <button
                        type="button"
                        onClick={() => setSource({ reviewId: r.id })}
                        className="rounded-md border border-line px-3 py-1.5 text-[13px] text-muted transition-colors hover:border-line-strong hover:text-ink"
                      >
                        {r.repositoryName}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </fieldset>

      <fieldset>
        <legend className="mb-1 font-display text-xl text-ink">Roles</legend>
        <p className="mb-4 text-sm text-muted">Which engineering roles should the scenarios test? Pick one or several.</p>
        <TagSelect
          label="Roles"
          options={ROLE_OPTIONS}
          value={roles}
          onChange={changeRoles}
          placeholder="Backend Engineer, Cloud Engineer…"
          allowCustom={false}
          maxTags={5}
        />
        {roleError && <p className="mt-1.5 text-xs text-danger">{roleError}</p>}
      </fieldset>

      <fieldset>
        <legend className="mb-1 font-display text-xl text-ink">Seniority</legend>
        <p className="mb-4 text-sm text-muted">This sets the engineering depth of each scenario, not its length.</p>
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {SENIORITY.map((s) => (
            <ChoiceCard key={s.value} name="seniority" checked={seniority === s.value} onSelect={() => setSeniority(s.value)} title={s.label} description={s.description} />
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend className="mb-4 font-display text-xl text-ink">Number of scenarios</legend>
        <div className="grid max-w-xl grid-cols-3 gap-2">
          {COUNTS.map((n) => (
            <ChoiceCard key={n} name="count" checked={count === n} onSelect={() => setCount(n)} title={String(n)} description={n === 5 ? 'Recommended' : undefined} />
          ))}
        </div>
        {count > 5 && <p className="mt-3 text-xs text-muted">Larger labs take longer to generate: every scenario is checked before you see it.</p>}
      </fieldset>

      <div className="space-y-3">
        <ErrorBanner error={start.error} />
        <Button type="submit" busy={start.isPending}>
          Generate Scenario Lab
        </Button>
        {start.isPending && (
          <p className="text-sm text-muted" role="status">
            Preparing repository context…
          </p>
        )}
        {!fromReview && (
          <p className="text-xs text-muted">
            No review needed. Want one first? <Link to="/dashboard" className="underline underline-offset-4 hover:text-ink">Review a repository</Link>.
          </p>
        )}
      </div>
    </form>
  )
}
