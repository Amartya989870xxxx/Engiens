import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { AiText, RevealScope } from './Reveal'

const TEXT = 'The fix prevents duplicate orders for sequential retries but not for concurrent ones.'

/** A browser where everything is visible at once and motion is allowed (or not). */
function browser({ reducedMotion }: { reducedMotion: boolean }) {
  window.matchMedia = ((query: string) => ({ matches: reducedMotion && query.includes('reduce'), media: query })) as unknown as typeof window.matchMedia
  globalThis.IntersectionObserver = class {
    cb: IntersectionObserverCallback
    constructor(cb: IntersectionObserverCallback) {
      this.cb = cb
    }
    observe() {
      this.cb([{ isIntersecting: true } as IntersectionObserverEntry], this as unknown as IntersectionObserver)
    }
    disconnect() {}
    unobserve() {}
    takeRecords() {
      return []
    }
    root = null
    rootMargin = ''
    thresholds = []
  } as unknown as typeof IntersectionObserver
}

function report(id = 'review:1') {
  return render(
    <RevealScope id={id}>
      <AiText as="p" text={TEXT} />
      <h2>Overall assessment</h2>
    </RevealScope>,
  )
}

/** The words a sighted reader can see right now (the full text is always available to screen readers). */
function visible() {
  const drawn = screen.getByText(TEXT, { selector: '.sr-only' }).nextElementSibling!
  const all = drawn.textContent ?? ''
  const notYet = drawn.querySelector('.opacity-0')?.textContent ?? ''
  return all.slice(0, all.length - notYet.length)
}

beforeEach(() => {
  sessionStorage.clear()
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'performance'] })
})
afterEach(() => {
  vi.useRealTimers()
  cleanup()
  // @ts-expect-error restoring jsdom's default (no IntersectionObserver)
  delete globalThis.IntersectionObserver
})

test('validated AI text types in on the first visit, then reads as plain text; headings never animate', async () => {
  browser({ reducedMotion: false })
  report()

  expect(screen.getByText(TEXT, { selector: '.sr-only' })).toBeInTheDocument() // screen readers get it all at once
  expect(visible()).toBe('')
  expect(screen.getByRole('heading', { name: 'Overall assessment' })).toBeInTheDocument()
  await act(() => vi.advanceTimersByTimeAsync(400))
  expect(visible().length).toBeGreaterThan(0)
  expect(visible().length).toBeLessThan(TEXT.length)
  await act(() => vi.advanceTimersByTimeAsync(2000))
  expect(screen.getByText(TEXT).tagName).toBe('P') // finished: one plain paragraph
})

test('a refresh or revisit in the same session shows the text instantly', async () => {
  browser({ reducedMotion: false })
  report()
  cleanup()
  report()
  expect(screen.getByText(TEXT).tagName).toBe('P')
  expect(screen.queryByText(TEXT, { selector: '.sr-only' })).not.toBeInTheDocument()
})

test('reduced motion always shows the text instantly', () => {
  browser({ reducedMotion: true })
  report('review:fresh')
  expect(screen.getByText(TEXT).tagName).toBe('P')
})

test('Escape or a click reveals everything at once', () => {
  browser({ reducedMotion: false })
  report('review:skip')
  expect(visible()).toBe('')
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(screen.getByText(TEXT).tagName).toBe('P')

  cleanup()
  sessionStorage.clear()
  report('review:click')
  fireEvent.click(screen.getByRole('heading', { name: 'Overall assessment' }))
  expect(screen.getByText(TEXT).tagName).toBe('P')
})
