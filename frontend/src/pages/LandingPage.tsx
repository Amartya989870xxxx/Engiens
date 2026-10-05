import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../useAuth'
import { BRAND } from '../brand'

/** The eight review dimensions from the product's review rubric. */
const DIMENSIONS = [
  { name: 'Code Quality', tags: ['Readability', 'Naming'], looksAt: 'Readability, naming, duplicated logic, and functions that try to do too much.' },
  { name: 'Architecture', tags: ['Layers', 'Coupling'], looksAt: 'Separation of concerns, which way dependencies point, and where business logic lives.' },
  { name: 'Error Handling', tags: ['Validation', 'Failures'], looksAt: 'Missing validation, swallowed exceptions, and API errors nobody can act on.' },
  { name: 'Testing', tags: ['Coverage'], looksAt: 'Important paths with no tests, untested edge cases, and where an integration test would pay off.' },
  { name: 'Security', tags: ['Secrets', 'Input'], looksAt: 'Exposed secrets, unsafe input, missing authorisation checks. Not a full audit. The obvious, caught early.' },
  { name: 'Persistence', tags: ['Schema', 'Queries'], looksAt: 'Schema design, transaction boundaries, missing indexes, and N+1 queries.' },
  { name: 'Performance', tags: ['Scale'], looksAt: 'Expensive loops, repeated external calls, missing caches, and work that won’t survive growth.' },
  { name: 'Production Readiness', tags: ['Logging', 'Config'], looksAt: 'Logging, configuration, observability, and what breaks at 3 a.m.' },
]

const SCENARIOS = [
  { name: 'The /feed slowdown', tags: ['Latency', 'Database'] },
  { name: 'Charged twice', tags: ['Reliability'] },
  { name: 'Ten times the traffic', tags: ['Scaling'] },
  { name: 'Stale after deploy', tags: ['Caching'] },
]

const PRINCIPLES = [
  'Every finding explains why it matters',
  'Your level tunes the advice. It never grades you',
  'One open rubric, no claimed company secrets',
  'Read-only access to only the repos you choose',
]

export function LandingPage() {
  const { user } = useAuth()
  return (
    <div className="min-h-screen bg-canvas">
      <div className="mx-auto min-h-screen max-w-[46rem] border-line px-5 sm:border-x sm:px-7">
        <TopBar signedIn={user !== null} />

        <main>
          <Hero signedIn={user !== null} />
          <ReviewIndex />
          <Section id="lab" title={<>Scenario Lab <Pill tone="light">Soon</Pill></>} lede="Production incidents to reason through. We evaluate how you think, not just your final answer. What would you check first, and why?">
            <IndexList>
              {SCENARIOS.map((s, i) => (
                <Entry key={s.name} number={SCENARIOS.length - i} tags={[<LockPill key="lock" />, ...s.tags]}>
                  {s.name}
                </Entry>
              ))}
            </IndexList>
          </Section>
          <Section id="principles" title="first/principles" lede={`What ${BRAND} will and won’t claim.`}>
            <IndexList>
              {PRINCIPLES.map((p, i) => (
                <Entry key={p} number={PRINCIPLES.length - i} size="sm">
                  {p}
                </Entry>
              ))}
            </IndexList>
          </Section>
        </main>

        <Footer />
      </div>
    </div>
  )
}

function TopBar({ signedIn }: { signedIn: boolean }) {
  return (
    <header className="flex items-center justify-between gap-3 py-6">
      <div className="flex items-center gap-3">
        <img src="/engiens-180.png" alt={BRAND} width={44} height={44} className="rounded-[22%]" />
        <nav aria-label="Sections" className="hidden items-center gap-1 rounded-2xl bg-raised p-1 sm:flex">
          <a href="#review" className="rounded-xl px-3 py-1.5 text-[13px] text-muted transition-colors hover:bg-line hover:text-ink">Review</a>
          <a href="#lab" className="rounded-xl px-3 py-1.5 text-[13px] text-muted transition-colors hover:bg-line hover:text-ink">Lab</a>
          <a href="#principles" className="rounded-xl px-3 py-1.5 text-[13px] text-muted transition-colors hover:bg-line hover:text-ink">Principles</a>
        </nav>
      </div>
      <div className="flex items-center gap-2">
        {signedIn ? (
          <PillLink to="/dashboard" primary>Open dashboard</PillLink>
        ) : (
          <>
            <PillLink to="/login">Log in</PillLink>
            <PillLink to="/register" primary>Get started</PillLink>
          </>
        )}
      </div>
    </header>
  )
}

function PillLink({ to, primary, children }: { to: string; primary?: boolean; children: ReactNode }) {
  return (
    <Link
      to={to}
      className={`inline-flex h-11 items-center rounded-2xl px-4 text-sm transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white ${
        primary ? 'bg-white text-black hover:bg-white/85' : 'bg-[#cfcfcf] text-black hover:bg-[#e2e2e2]'
      }`}
    >
      {children}
    </Link>
  )
}

/** Bright words carry the meaning; grey words connect them. Reading only the white words still makes sense. */
const W = ({ children }: { children: ReactNode }) => <span className="text-ink">{children}</span>

function Hero({ signedIn }: { signedIn: boolean }) {
  return (
    <section className="pb-20 pt-10 sm:pt-14">
      <h1 className="text-[2.15rem] font-normal leading-[1.08] tracking-[-0.03em] text-muted animate-fade-in motion-reduce:animate-none sm:text-[2.9rem]">
        <W>{BRAND}</W> reviews the <W>code you actually built</W>, tuned to <W>where you are now</W>, then puts you
        inside the <W>production incidents</W> most courses skip. Don’t just write code. <W>Engineer it.</W>
      </h1>
      <div className="mt-10 flex flex-wrap items-center gap-x-5 gap-y-3">
        <PillLink to={signedIn ? '/dashboard' : '/register'} primary>Review your first repository →</PillLink>
        <span className="font-display text-[15px] italic text-muted">Start with a repository you built yourself.</span>
      </div>
    </section>
  )
}

function ReviewIndex() {
  // Hovering or focusing a dimension shows what that part of the review looks at.
  const [active, setActive] = useState<number | null>(null)
  const caption = active === null ? 'Eight dimensions, one open rubric. Point at one to see what it covers.' : DIMENSIONS[active].looksAt
  return (
    <Section id="review" title="Review Index">
      <IndexList onLeave={() => setActive(null)}>
        {DIMENSIONS.map((d, i) => (
          <Entry
            key={d.name}
            number={DIMENSIONS.length - i}
            tags={d.tags}
            dimmed={active !== null && active !== i}
            onActivate={() => setActive(i)}
          >
            {d.name}
          </Entry>
        ))}
      </IndexList>
      <p aria-live="polite" className="mt-6 min-h-[3.5rem] max-w-xl pl-6 font-display text-lg italic leading-snug text-muted">
        {caption}
      </p>
    </Section>
  )
}

function Section({ id, title, lede, children }: { id: string; title: ReactNode; lede?: string; children: ReactNode }) {
  return (
    <section id={id} className="scroll-mt-6 border-t border-line pb-20 pt-6">
      <h2 className="flex items-center gap-3 text-[2.15rem] leading-[1.1] tracking-[-0.03em] text-ink sm:text-[2.6rem]">{title}</h2>
      {lede && <p className="mb-4 max-w-[40rem] text-[1.6rem] leading-[1.12] tracking-[-0.025em] text-muted sm:text-[2.1rem]">{lede}</p>}
      {children}
    </section>
  )
}

function IndexList({ children, onLeave }: { children: ReactNode; onLeave?: () => void }) {
  return (
    <ul onMouseLeave={onLeave} className="mt-2 space-y-1 border-l-[3px] border-line-strong pl-5">
      {children}
    </ul>
  )
}

type EntryProps = {
  number: number
  tags?: ReactNode[]
  size?: 'lg' | 'sm'
  dimmed?: boolean
  onActivate?: () => void
  children: ReactNode
}

function Entry({ number, tags = [], size = 'lg', dimmed = false, onActivate, children }: EntryProps) {
  const text = size === 'lg' ? 'text-[1.75rem] sm:text-[2.35rem]' : 'text-[1.4rem] sm:text-[1.9rem]'
  const body = (
    <>
      {tags.map((t, i) => (typeof t === 'string' ? <Pill key={t}>{t}</Pill> : <span key={i}>{t}</span>))}
      <span className={`${text} leading-[1.15] tracking-[-0.03em]`}>
        {children}
        <sup className="ml-1 align-super font-mono text-[10px] tracking-normal text-muted">{String(number).padStart(2, '0')}</sup>
      </span>
    </>
  )
  const layout = `flex flex-wrap items-center gap-1.5 transition-colors duration-200 ${dimmed ? 'text-muted/50' : 'text-ink'}`
  return (
    <li>
      {onActivate ? (
        <button type="button" onMouseEnter={onActivate} onFocus={onActivate} className={`${layout} text-left focus-visible:outline-none`}>
          {body}
        </button>
      ) : (
        <div className={layout}>{body}</div>
      )}
    </li>
  )
}

function Pill({ children, tone = 'dark' }: { children: ReactNode; tone?: 'dark' | 'light' }) {
  return (
    <span
      className={`inline-flex h-7 items-center rounded-full px-2.5 text-[11px] font-medium uppercase tracking-[0.06em] ${
        tone === 'light' ? 'bg-[#cfcfcf] text-black' : 'bg-raised text-ink/80'
      }`}
    >
      {children}
    </span>
  )
}

function LockPill() {
  return (
    <span aria-label="Locked" className="inline-flex size-7 items-center justify-center rounded-full bg-raised text-ink/80">
      <svg viewBox="0 0 24 24" className="size-3.5" fill="currentColor" aria-hidden>
        <path d="M7 10V7a5 5 0 0 1 10 0v3h1a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zm2 0h6V7a3 3 0 0 0-6 0z" />
      </svg>
    </span>
  )
}

function Footer() {
  return (
    <footer className="grid gap-8 border-t border-line py-10 font-display text-[15px] leading-snug text-muted sm:grid-cols-[8rem_1fr_9rem]">
      <img src="/engiens-180.png" alt="" width={40} height={40} className="rounded-[22%] opacity-90" />
      <div>
        <p className="max-w-[16rem]">Built for students and early-career engineers. New features ship regularly.</p>
        <LocalTime />
      </div>
      <ul className="space-y-0.5">
        <li><Link to="/login" className="hover:text-ink">Log in ↗</Link></li>
        <li><Link to="/register" className="hover:text-ink">Get started ↗</Link></li>
        <li><a href="#principles" className="hover:text-ink">The principles ↗</a></li>
      </ul>
    </footer>
  )
}

const formatNow = () =>
  new Date().toLocaleString('en-US', { weekday: 'short', month: 'short', day: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })

/** The visitor's own date and time, like the reference site's footer clock. */
function LocalTime() {
  const [now, setNow] = useState(formatNow)
  useEffect(() => {
    const id = window.setInterval(() => setNow(formatNow()), 30_000)
    return () => window.clearInterval(id)
  }, [])
  return (
    <p className="mt-4 flex items-center gap-2">
      <span aria-hidden className="size-3 rounded-full bg-[radial-gradient(circle_at_35%_30%,#ffffff,#8a8a8a_55%,#2a2a2a)]" />
      <time>{now}</time>
    </p>
  )
}
