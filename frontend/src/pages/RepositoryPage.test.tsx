import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import type { ImportedRepository } from '../api'
import { reviewRun } from '../review/fixture'
import { RepositoryPage } from './RepositoryPage'

afterEach(() => vi.restoreAllMocks())

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const ready: ImportedRepository = {
  id: 'repo-1', owner: 'asha', name: 'orders-api', url: 'https://github.com/asha/orders-api', description: 'Order service',
  defaultBranch: 'main', commitSha: 'c0ffee1234567890c0ffee1234567890c0ffee12', primaryLanguage: 'TypeScript', visibility: 'PRIVATE', stars: 1200, forks: 3, fileCount: 54,
  relevantFileCount: 39, ignoredFileCount: 15, status: 'READY', failureReason: null, updatedAt: '2026-10-04T10:00:00Z',
  languages: [{ label: 'TypeScript', count: 30 }, { label: 'JSON', count: 9 }],
  ignoredReasons: [{ label: 'Dependency directory', count: 12 }, { label: 'Image asset', count: 3 }],
}

function renderAt(id: string) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/repositories/${id}`]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const notPrepared = () => json({ status: 404, code: 'ANALYSIS_NOT_FOUND', message: 'Not prepared yet.', fieldErrors: {} }, 404)

/** Routes by URL: the repository, its latest preparation run, the prepare action and its reviews. */
function mockServer(repo: unknown, latest: () => Response = notPrepared, prepare?: () => Response, reviews: unknown[] = []) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.endsWith('/reviews') && init?.method === 'POST') return json(reviewRun({ id: 'rev-new', status: 'QUEUED', review: null }))
    if (url.includes('/reviews')) return json(reviews)
    if (url.endsWith('/analyses/latest')) return latest()
    if (url.endsWith('/analyses') && init?.method === 'POST' && prepare) return prepare()
    return json(repo)
  })
}

test('shows the imported repository’s metadata and file inventory', async () => {
  mockServer(ready)
  renderAt('repo-1')

  expect(await screen.findByRole('heading', { name: 'orders-api' })).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('Imported and ready for analysis.')
  expect(screen.getByText('Order service')).toBeInTheDocument()
  expect(screen.getByText('Private')).toBeInTheDocument()
  expect(screen.getByText('main')).toBeInTheDocument()
  expect(screen.getByText('1,200')).toBeInTheDocument()
  expect(screen.getByText('54')).toBeInTheDocument()
  expect(screen.getByText('39')).toBeInTheDocument()
  expect(screen.getByText('Dependency directory')).toBeInTheDocument()
  // Not reviewed yet: one clear action, no history.
  expect(screen.getByRole('button', { name: 'Run review' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: 'Open latest review' })).not.toBeInTheDocument()
  expect(screen.queryByRole('list', { name: 'Review history' })).not.toBeInTheDocument()
})

test('a failed import explains why and can be retried', async () => {
  const failed = { ...ready, status: 'FAILED', failureReason: 'This repository is larger than the current Engiens review limit. Try a smaller repository for now.', fileCount: 0, relevantFileCount: 0, ignoredFileCount: 0, languages: [], ignoredReasons: [] }
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    if (url.endsWith('/import')) return json({ ...ready })
    if (url.includes('/reviews')) return json([])
    return url.endsWith('/analyses/latest') ? notPrepared() : json(failed)
  })
  renderAt('repo-1')

  expect(await screen.findByText(/larger than the current Engiens review limit/)).toBeInTheDocument()
  expect(screen.queryByText('File inventory')).not.toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Try importing again' }))
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/api\/repositories\/import$/), expect.objectContaining({ method: 'POST' }))
  expect(await screen.findByText('Imported and ready for analysis.')).toBeInTheDocument()
})

test('someone else’s or a missing repository shows a clear message', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    json({ status: 404, code: 'REPOSITORY_NOT_FOUND', message: 'We couldn’t find that repository.', fieldErrors: {} }, 404),
  )
  renderAt('someone-elses')
  expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t find that repository.')
  expect(screen.getByRole('link', { name: 'Back to the dashboard' })).toBeInTheDocument()
})

test('preparing for review shows what was detected and how much context was chosen', async () => {
  const prepared = {
    id: 'run-1', repositoryId: 'repo-1', status: 'COMPLETED', commitSha: 'c0ffee1234567890', failureReason: null,
    completedAt: '2026-10-04T10:00:00Z', durationMs: 900,
    stats: { filesFetched: 12, bytesFetched: 40_000, signalCount: 17, contextFileCount: 9, contextBytes: 31_000 },
    profile: {
      languages: [{ name: 'TypeScript', fileCount: 30, percentage: 76.9 }],
      frameworks: [{ name: 'React', confidence: 'HIGH', evidence: ['package.json declares react'] }],
      databases: [{ name: 'PostgreSQL', confidence: 'HIGH', evidence: ['package.json declares pg'] }],
    },
  }
  const fetch = mockServer(ready, notPrepared, () => json(prepared))
  renderAt('repo-1')

  await userEvent.click(await screen.findByRole('button', { name: 'Prepare for review' }))

  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/api\/repositories\/repo-1\/analyses$/), expect.objectContaining({ method: 'POST' }))
  expect(await screen.findByText(/Prepared for review/)).toHaveTextContent('Prepared for review from commit c0ffee1.')
  expect(screen.getByText('React, PostgreSQL')).toBeInTheDocument()
  expect(screen.getByText('17')).toBeInTheDocument()
  expect(screen.getByText('9 · 30 KB')).toBeInTheDocument()
  // Preparation is not a review: nothing has been reviewed yet.
  expect(screen.getByRole('button', { name: 'Run review' })).toBeEnabled()
})

test('a failed preparation shows the server’s reason and can be retried', async () => {
  const failed = { id: 'run-2', repositoryId: 'repo-1', status: 'FAILED', commitSha: null,
    failureReason: 'GitHub is temporarily limiting requests. Please try again later.', completedAt: null, durationMs: 10,
    stats: { filesFetched: null, bytesFetched: null, signalCount: null, contextFileCount: null, contextBytes: null }, profile: null }
  mockServer(ready, () => json(failed))
  renderAt('repo-1')
  expect(await screen.findByText(/temporarily limiting requests/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
})

test('running a review starts it and opens the review page', async () => {
  const fetch = mockServer(ready)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/repositories/repo-1']}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryPage />} />
          <Route path="/reviews/:id" element={<p>Review rev-new</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await userEvent.click(await screen.findByRole('button', { name: 'Run review' }))

  expect(await screen.findByText('Review rev-new')).toBeInTheDocument()
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/api\/repositories\/repo-1\/reviews$/), expect.objectContaining({ method: 'POST' }))
})

test('a reviewed repository offers the latest review, a fresh one, and its history', async () => {
  const fetch = mockServer(ready, notPrepared, undefined, [
    reviewRun({ id: 'rev-3', status: 'FAILED', review: null, model: null, createdAt: '2026-10-05T10:00:00Z' }),
    reviewRun(),
  ])
  renderAt('repo-1')

  expect(await screen.findByRole('button', { name: 'Open latest review' })).toBeInTheDocument()
  const history = screen.getByRole('list', { name: 'Review history' })
  expect(within(history).getAllByRole('link')).toHaveLength(2)
  expect(within(history).getByText('Failed')).toBeInTheDocument()
  expect(within(history).getByText('gemini-3.8-flash')).toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Review again' }))
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/reviews\?regenerate=true$/), expect.objectContaining({ method: 'POST' }))
})
