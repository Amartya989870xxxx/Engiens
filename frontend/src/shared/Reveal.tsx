import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ElementType,
  type ReactNode,
} from 'react'

/**
 * A restrained typewriter reveal for AI-written text that the backend has ALREADY parsed and validated.
 * Nothing streams from the model: this only animates finished, trusted data.
 *
 * - Plays on the first visit to a report in this browser session; a refresh or a revisit shows text instantly.
 * - prefers-reduced-motion, or a browser without IntersectionObserver: always instant.
 * - Each block starts when it scrolls into view and takes 0.3 to 1.4 s; blocks are staggered, not simultaneous.
 * - Click or Esc anywhere in the report reveals everything at once.
 * - Layout never jumps (unrevealed words are present but transparent) and screen readers get the full text.
 */

const SEEN_KEY = 'lens.revealed'
const MAX_REMEMBERED = 200
const STAGGER_MS = 140

type Scope = { animate: boolean; skipped: boolean; slot: () => number; release: () => void }

const RevealContext = createContext<Scope>({ animate: false, skipped: true, slot: () => 0, release: () => {} })

function prefersReducedMotion() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function seen(): string[] {
  try {
    return JSON.parse(sessionStorage.getItem(SEEN_KEY) ?? '[]') as string[]
  } catch {
    return []
  }
}

function markSeen(id: string) {
  try {
    const ids = seen().filter((s) => s !== id)
    ids.push(id)
    sessionStorage.setItem(SEEN_KEY, JSON.stringify(ids.slice(-MAX_REMEMBERED)))
  } catch {
    // Storage unavailable (private mode): the reveal may replay, which is harmless.
  }
}

/** Wraps one report. `id` identifies it, e.g. "review:<id>", so each report plays once per session. */
export function RevealScope({ id, children, className }: { id: string; children: ReactNode; className?: string }) {
  const [animate] = useState(
    () => typeof IntersectionObserver !== 'undefined' && !prefersReducedMotion() && !seen().includes(id),
  )
  const [skipped, setSkipped] = useState(false)
  const active = useRef(0)

  useEffect(() => {
    if (animate) markSeen(id)
  }, [animate, id])

  useEffect(() => {
    if (!animate || skipped) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setSkipped(true)
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [animate, skipped])

  const slot = useCallback(() => active.current++, [])
  const release = useCallback(() => {
    active.current = Math.max(0, active.current - 1)
  }, [])
  const value = useMemo(() => ({ animate, skipped, slot, release }), [animate, skipped, slot, release])

  return (
    <RevealContext.Provider value={value}>
      {/* Any click inside the report means "I'm reading": show everything. */}
      <div className={className} onClickCapture={animate && !skipped ? () => setSkipped(true) : undefined}>
        {children}
      </div>
    </RevealContext.Provider>
  )
}

/** One block of AI-written text. Renders plain text unless its scope is revealing. */
export function AiText({ text, as: Tag = 'span', className }: { text: string; as?: ElementType; className?: string }) {
  const scope = useContext(RevealContext)
  const ref = useRef<HTMLElement>(null)
  const [progress, setProgress] = useState(scope.animate && !scope.skipped ? 0 : 1)
  const done = !scope.animate || scope.skipped || progress >= 1
  const tokens = useMemo(() => text.split(/(\s+)/), [text])

  useEffect(() => {
    if (done) return
    let raf = 0
    let timer: ReturnType<typeof setTimeout> | undefined
    let started = false
    const duration = Math.min(1400, Math.max(300, tokens.length * 20))
    const begin = () => {
      started = true
      const delay = scope.slot() * STAGGER_MS
      timer = setTimeout(() => {
        let start: number | null = null
        const tick = (t: number) => {
          start ??= t
          const p = Math.min(1, (t - start) / duration)
          setProgress(p)
          if (p < 1) raf = requestAnimationFrame(tick)
          else scope.release()
        }
        raf = requestAnimationFrame(tick)
      }, delay)
    }
    const el = ref.current
    if (!el) {
      begin()
    } else {
      const io = new IntersectionObserver(
        (entries) => {
          if (entries.some((e) => e.isIntersecting)) {
            io.disconnect()
            begin()
          }
        },
        { rootMargin: '0px 0px -8% 0px' },
      )
      io.observe(el)
      return () => {
        io.disconnect()
        clearTimeout(timer)
        cancelAnimationFrame(raf)
        if (started) scope.release()
      }
    }
    return () => {
      clearTimeout(timer)
      cancelAnimationFrame(raf)
      if (started) scope.release()
    }
    // scope functions are stable; restart only if the text itself changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [done, tokens])

  if (done) return <Tag className={className}>{text}</Tag>
  const shown = Math.floor(tokens.length * progress)
  return (
    <Tag ref={ref} className={className}>
      <span className="sr-only">{text}</span>
      <span aria-hidden>
        {tokens.slice(0, shown).join('')}
        <span className="opacity-0">{tokens.slice(shown).join('')}</span>
      </span>
    </Tag>
  )
}
