import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { reviewRun } from '../review/fixture'
import { ReviewPage } from './ReviewPage'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

beforeEach(() => {
  // jsdom has no layout, so there's nothing to scroll; record the call instead.
  Element.prototype.scrollIntoView = vi.fn()
})
afterEach(() => vi.restoreAllMocks())

function renderAt(id: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[`/reviews/${id}`]}>
        <Routes>
          <Route path="/reviews/:id" element={<ReviewPage />} />
          <Route path="/repositories/:id" element={<p>Repository page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('a running review shows progress, then the report once it completes', async () => {
  let calls = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    json(++calls === 1 ? reviewRun({ status: 'RUNNING', review: null, completedAt: null, model: null }) : reviewRun()),
  )
  renderAt('rev-1')

  expect(await screen.findByRole('status')).toHaveTextContent('Reviewing the prepared evidence')
  // Polls every three seconds until the run finishes.
  expect(await screen.findByText('A clear API with thin tests.', {}, { timeout: 5000 })).toBeInTheDocument()
  expect(screen.getByRole('heading', { level: 1, name: 'orders-api' })).toBeInTheDocument()
  expect(screen.getByText('Reviewed with gemini-3.8-flash')).toBeInTheDocument()
  expect(screen.getByText('c0ffee1')).toBeInTheDocument()
}, 10_000)

test('a failed review shows the reason and can be started again', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) =>
    init?.method === 'POST'
      ? json(reviewRun({ id: 'rev-2', status: 'QUEUED', review: null }))
      : json(reviewRun({ status: 'FAILED', review: null, errorCode: 'AI_UNAVAILABLE', errorMessage: 'The AI reviewer is busy right now. Please try again in a few minutes.' })),
  )
  renderAt('rev-1')

  expect(await screen.findByRole('alert')).toHaveTextContent('The AI reviewer is busy right now.')
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/api\/repositories\/repo-1\/reviews$/), expect.objectContaining({ method: 'POST' }))
})

test('a review that is missing or someone else’s shows a clear message', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    json({ status: 404, code: 'REVIEW_NOT_FOUND', message: 'We couldn’t find that review.', fieldErrors: {} }, 404),
  )
  renderAt('someone-elses')
  expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t find that review.')
})

test('the index opens a dimension; findings show evidence and personalised advice', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(reviewRun()))
  renderAt('rev-1')

  const index = await screen.findByRole('list', { name: 'Dimensions' })
  await userEvent.click(within(index).getByRole('button', { name: /Error handling/ }))

  const section = document.getElementById('dim-ERROR_HANDLING')!
  expect(within(section).getByRole('button', { name: /Error handling/ })).toHaveAttribute('aria-expanded', 'true')
  expect(within(section).getByText('Database errors are swallowed')).toBeInTheDocument()
  expect(within(section).getByText('app/orders.py')).toBeInTheDocument()
  expect(within(section).getByText('ERR_BROAD_EXCEPT')).toBeInTheDocument()
  expect(within(section).getByText(/never returning success from an except block/)).toBeInTheDocument()
  await vi.waitFor(() => expect(Element.prototype.scrollIntoView).toHaveBeenCalled())
})

test('evidence lines expand into the exact reviewed code', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
    String(input).includes('/excerpt')
      ? json({ file: 'app/orders.py', lineStart: 12, lineEnd: 14, lines: [{ number: 12, text: 'try:' }, { number: 13, text: '    save(order)' }, { number: 14, text: 'except Exception:' }] })
      : json(reviewRun()),
  )
  renderAt('rev-1')

  await userEvent.click(await screen.findByRole('button', { name: 'Expand all' }))
  await userEvent.click(screen.getByRole('button', { name: /Lines 12–14/ }))

  expect(await screen.findByText('except Exception:')).toBeInTheDocument()
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/reviews/rev-1/excerpt?file=app%2Forders.py&lineStart=12&lineEnd=14'), expect.anything())
})

test('a dimension that does not apply is shown as not assessable, not as a low grade', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(reviewRun()))
  renderAt('rev-1')

  await screen.findByRole('list', { name: 'Dimensions' })
  const section = document.getElementById('dim-OBSERVABILITY')!
  expect(within(section).getByText('Not assessable')).toBeInTheDocument()
  await userEvent.click(within(section).getByRole('button', { name: /Observability/ }))
  expect(within(section).getByText('Nothing to assess here.')).toBeInTheDocument()
  expect(within(section).queryByText(/Confidence:/)).not.toBeInTheDocument()
  expect(screen.getByText(/Explanations written for: undergraduate, year 2/)).toBeInTheDocument()
})
