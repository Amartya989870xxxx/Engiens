import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { Button, ErrorBanner } from '../ui'
import { initialDraft, saveBeforeLeaving, STEP_COUNT, stepError, toRequest, type Draft } from '../profile/draft'
import { useGitHubLookup, useKeepDraftOnExpiry, useRestoredDraft, useSaveProfile } from '../profile/profileHooks'
import { returnErrorMessage, useGitHubAccess } from '../profile/useGitHubAccess'
import { AboutStep, ExperienceStep, GoalsStep, StackStep } from '../profile/steps'

const FADE_MS = 200

const STEPS = [
  { title: 'First, tell us about you', subtitle: 'Feedback is pitched at where you are right now.' },
  { title: 'What do you build with?', subtitle: 'Pick from the lists, or type anything that’s missing.' },
  { title: 'What have you worked on?', subtitle: 'Your areas of experience and the projects you’ve shipped.' },
  { title: 'What are you working toward?', subtitle: 'This shapes which improvements we point you to first.' },
]

/**
 * First-time setup, shown once. Users who already have a profile never reach this page
 * (see RequireNoProfile in components.tsx); they edit it on /profile instead.
 */
export function OnboardingPage() {
  const { user } = useAuth()
  return <Onboarding initial={initialDraft(null, user?.name ?? '')} />
}

const prefersReducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

const GITHUB_STEP = 2

function Onboarding({ initial }: { initial: Draft }) {
  const navigate = useNavigate()
  const { githubOutcome, saved } = useRestoredDraft()
  const [draft, setDraft] = useState<Draft>(() => {
    const start = saved?.draft ?? initial
    return githubOutcome ? { ...start, githubAccess: 'private' } : start
  })
  const [step, setStep] = useState(() => (githubOutcome ? GITHUB_STEP : (saved?.step ?? 0)))
  const [visible, setVisible] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const fadeTimer = useRef<number | undefined>(undefined)
  const firstRender = useRef(true)

  const { lookup, verify, checking } = useGitHubLookup(draft.githubUrl.trim())
  const save = useSaveProfile(() => navigate('/dashboard'))
  const access = useGitHubAccess(() => saveBeforeLeaving(draft, step), returnErrorMessage(githubOutcome))
  const githubLogin = access.connection?.connected ? access.connection.login : null

  useEffect(() => () => window.clearTimeout(fadeTimer.current), [])
  useKeepDraftOnExpiry(draft, step)

  // Move focus to the new question so keyboard and screen-reader users follow the transition.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    headingRef.current?.focus({ preventScroll: true })
  }, [step])

  function goTo(next: number) {
    setError(null)
    setVisible(false)
    fadeTimer.current = window.setTimeout(
      () => {
        setStep(next)
        setVisible(true)
      },
      prefersReducedMotion() ? 0 : FADE_MS,
    )
  }

  async function next(e: FormEvent) {
    e.preventDefault()
    const problem = stepError(step, draft, githubLogin !== null)
    if (problem) return setError(problem)

    if (step === GITHUB_STEP && !(await verify(draft))) return

    if (step < STEP_COUNT - 1) goTo(step + 1)
    else save.mutate(toRequest(draft, githubLogin))
  }

  const isLast = step === STEP_COUNT - 1
  const { title, subtitle } = STEPS[step]

  return (
    <div className="mx-auto w-full max-w-lg pt-4 sm:pt-12">
      <div className="mb-8 flex items-center gap-4">
        <div className="flex flex-1 gap-1.5" aria-hidden>
          {STEPS.map((_, i) => (
            <span key={i} className={`h-0.5 flex-1 rounded-full transition-colors duration-500 ${i <= step ? 'bg-white' : 'bg-line'}`} />
          ))}
        </div>
        <span className="font-mono text-xs tabular-nums text-muted">
          Step {step + 1} of {STEP_COUNT}
        </span>
      </div>

      <form
        onSubmit={next}
        noValidate
        className={`rounded-xl border border-line bg-surface p-6 transition-[opacity,transform] duration-200 ease-out motion-reduce:transition-none sm:p-8 ${
          visible ? 'translate-y-0 opacity-100' : 'translate-y-1 opacity-0'
        }`}
      >
        <h1 ref={headingRef} tabIndex={-1} className="font-display text-[2rem] leading-tight tracking-[-0.01em] text-ink focus:outline-none">
          {title}
        </h1>
        <p className="mt-2 text-sm text-muted">{subtitle}</p>

        <div className="mt-7 space-y-5">
          {step === 0 && <AboutStep draft={draft} update={setDraft} />}
          {step === 1 && <StackStep draft={draft} update={setDraft} />}
          {step === 2 && (
            <ExperienceStep
              draft={draft}
              update={setDraft}
              lookup={lookup}
              access={access}
            />
          )}
          {step === 3 && <GoalsStep draft={draft} update={setDraft} />}
        </div>

        {error && (
          <p role="alert" className="mt-5 text-sm text-danger">
            {error}
          </p>
        )}
        {isLast && (
          <div className="mt-5">
            <ErrorBanner error={save.error} />
          </div>
        )}

        <div className="mt-8 flex items-center justify-between">
          {step > 0 ? (
            <Button type="button" variant="ghost" className="-ml-3" onClick={() => goTo(step - 1)}>
              ← Back
            </Button>
          ) : (
            <span />
          )}
          <Button type="submit" busy={save.isPending || (step === GITHUB_STEP && checking)}>
            {isLast ? 'Save profile' : 'Continue'}
          </Button>
        </div>
      </form>

      <p className="mt-6 text-center text-xs text-muted">
        Your profile tailors feedback to your level. It isn’t a measure of your ability.
      </p>
    </div>
  )
}
