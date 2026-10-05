import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import type { ProgressHistoryItem } from '../api'
import { emptyProgress, oneReviewProgress, richProgress } from './fixture'
import { ProgressPage } from './ProgressPage'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
afterEach(() => vi.restoreAllMocks())

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

function renderProgress(path = '/progress') {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/progress" element={<><ProgressPage /><Where /></>} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const historyItem = (overrides: Partial<ProgressHistoryItem> = {}): ProgressHistoryItem => ({
  type: 'REVIEW',
  id: 'rev-1',
  repositoryId: 'repo-1',
  repositoryName: 'orders-api',
  commitSha: 'abc1234def5678',
  date: '2026-10-01T10:00:00Z',
  overall: 'SOLID',
  roles: null,
  seniority: null,
  scenariosGenerated: null,
  scenariosSubmitted: null,
  ...overrides,
})

function serve(progress: unknown, history: { items: ProgressHistoryItem[]; page: number; hasMore: boolean }[] = [{ items: [], page: 0, hasMore: false }]) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    if (url.includes('/api/progress/history')) {
      const page = Number(new URL(url).searchParams.get('page') ?? 0)
      return json(history[page])
    }
    return json(progress)
  })
}

test('with no reviews or labs, progress explains how it starts', async () => {
  serve(emptyProgress())
  renderProgress()

  expect(await screen.findByRole('heading', { name: 'Progress starts with your first review' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Review a repository' })).toHaveAttribute('href', '/dashboard')
  expect(screen.queryByRole('heading', { name: 'What the evidence shows' })).not.toBeInTheDocument()
  expect(screen.getByText(/not a measure of your overall engineering ability or industry readiness/)).toBeInTheDocument()
})

test('one review shows its evidence but no trend, and points to Scenario Lab', async () => {
  serve(oneReviewProgress(), [{ items: [historyItem()], page: 0, hasMore: false }])
  renderProgress()

  expect(await screen.findByText('Based on 1 review · Oct 1, 2026.')).toBeInTheDocument()
  expect(screen.getByText(/Not enough history to identify a trend yet/)).toBeInTheDocument()
  expect(screen.getAllByText('Not enough history').length).toBe(16)
  expect(screen.getByText(/No completed Scenario Lab yet/)).toBeInTheDocument()
  expect(screen.getByText('Worth practising first: Testing & Quality Assurance.')).toBeInTheDocument()
  // The latest review's teaching, quoted with where it came from.
  expect(screen.getByText('Integration tests for the order flow')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Practise in Scenario Lab' })).toHaveAttribute('href', '/scenario-lab?review=rev-1')
})

test('each indicator shows its reason and expands to the evidence behind it, with links', async () => {
  const user = userEvent.setup()
  serve(richProgress())
  renderProgress()

  const strengths = await screen.findByRole('region', { name: 'Consistent strengths' })
  expect(within(strengths).getByText('Concurrency & Consistency')).toBeInTheDocument()
  expect(within(strengths).getByText(/Solid or Strong in 2 of 2 assessments/)).toBeInTheDocument()
  await user.click(within(strengths).getByText('Evidence (2)'))
  const items = within(strengths).getAllByRole('listitem').slice(1)
  expect(items[0]).toHaveTextContent('Scenario Lab')
  expect(items[0]).toHaveTextContent('Prevent duplicate orders under retries')
  expect(items[0]).toHaveTextContent('orders-api · abc1234 · Concurrency consistency')
  expect(items[0]).toHaveTextContent('Strong')
  expect(within(items[0]).getByRole('link', { name: 'Open lab' })).toHaveAttribute('href', '/repositories/repo-1/scenario-labs/lab-1')
  expect(within(items[1]).getByRole('link', { name: 'Open review' })).toHaveAttribute('href', '/reviews/rev-1')

  const gaps = screen.getByRole('region', { name: 'Recurring gaps' })
  expect(within(gaps).getByText('Testing & Quality Assurance')).toBeInTheDocument()
  expect(within(screen.getByRole('region', { name: 'Improving' })).getByText('Not enough evidence across assessments yet.')).toBeInTheDocument()
})

test('a better rating at a later commit is shown as a project-level change, not as the developer improving', async () => {
  serve(richProgress())
  renderProgress()

  const changes = await screen.findByRole('region', { name: 'Project-level changes' })
  expect(changes).toHaveTextContent('Error Handling & Resilience in orders-api: Developing → Solid')
  expect(changes).toHaveTextContent('1111111')
  expect(changes).toHaveTextContent('describes how the project\'s code changed, not a measure of you')
  expect(within(screen.getByRole('region', { name: 'Improving' })).queryByText('Error Handling & Resilience')).not.toBeInTheDocument()
})

test('levels are words, never numbers or percentages', async () => {
  serve(richProgress())
  renderProgress()
  await screen.findByRole('heading', { name: 'Engineering areas' })
  expect(document.body.textContent).not.toMatch(/%|\/10|\/100|score/i)
})

test('practice shows categories, the area each counts under, roles and seniority', async () => {
  serve(richProgress())
  renderProgress()

  expect(await screen.findByText('Concurrency consistency')).toBeInTheDocument()
  expect(screen.getByText('Counts under Concurrency & Consistency')).toBeInTheDocument()
  expect(screen.getByText('2 answers: 1 Strong, 1 Solid')).toBeInTheDocument()
  expect(screen.getByText(/Backend Engineer \(2\)/)).toBeInTheDocument()
  expect(screen.getByText('From your latest lab')).toBeInTheDocument()
})

test('choosing a repository scopes progress to it, in the URL', async () => {
  const user = userEvent.setup()
  const fetch = serve(richProgress())
  renderProgress()

  await user.selectOptions(await screen.findByLabelText('Repositories'), 'payments')

  expect(screen.getByTestId('where')).toHaveTextContent('/progress?repository=repo-2')
  expect(fetch.mock.calls.some(([url]) => String(url).includes('/api/progress?repositoryId=repo-2'))).toBe(true)
})

test('history lists reviews and labs with links and loads more on request', async () => {
  const user = userEvent.setup()
  serve(oneReviewProgress(), [
    {
      items: [
        historyItem({ type: 'LAB', id: 'lab-1', overall: null, roles: ['BACKEND_ENGINEER'], seniority: 'SDE1', scenariosGenerated: 20, scenariosSubmitted: 2 }),
      ],
      page: 0,
      hasMore: true,
    },
    { items: [historyItem()], page: 1, hasMore: false },
  ])
  renderProgress()

  const lab = await screen.findByRole('link', { name: /Scenario Lab · orders-api/ })
  expect(lab).toHaveAttribute('href', '/repositories/repo-1/scenario-labs/lab-1')
  expect(lab).toHaveTextContent('Backend Engineer · SDE1 · 2 of 20 submitted')
  await user.click(screen.getByRole('button', { name: 'Show more' }))
  expect(await screen.findByRole('link', { name: /Review · orders-api/ })).toHaveAttribute('href', '/reviews/rev-1')
  expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument()
})

test('a repository that is not yours shows the error and a way back', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ code: 'REPOSITORY_NOT_FOUND', message: "We couldn't find that repository." }, 404))
  renderProgress('/progress?repository=someone-elses')

  expect(await screen.findByRole('alert')).toHaveTextContent("We couldn't find that repository.")
  expect(screen.getByRole('link', { name: 'Show all repositories' })).toHaveAttribute('href', '/progress')
})
