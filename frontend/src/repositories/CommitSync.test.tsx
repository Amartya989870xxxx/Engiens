import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import type { ImportedRepository } from '../api'
import { CommitSync } from './CommitSync'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
afterEach(() => vi.restoreAllMocks())

const repo = { id: 'repo-1', defaultBranch: 'main', commitSha: 'aaaaaaa1111' } as ImportedRepository

function renderSync() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <CommitSync repo={repo} />
    </QueryClientProvider>,
  )
}

test('says when the repository moved to a new commit, and that earlier reviews keep theirs', async () => {
  const user = userEvent.setup()
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    json({ repository: { ...repo, commitSha: 'bbbbbbb2222' }, changed: true, previousCommit: 'aaaaaaa1111' }),
  )
  renderSync()

  await user.click(screen.getByRole('button', { name: 'Check for new commits' }))

  expect(await screen.findByRole('status')).toHaveTextContent('Moved from aaaaaaa to bbbbbbb. Run a review to assess the new code; earlier reviews keep their commit.')
  expect(String(fetch.mock.calls[0][0])).toContain('/api/repositories/repo-1/sync')
})

test('says when it is already up to date, and shows why it can\'t move while work is running', async () => {
  const user = userEvent.setup()
  vi.spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(json({ repository: repo, changed: false, previousCommit: 'aaaaaaa1111' }))
    .mockResolvedValueOnce(json({ code: 'REPOSITORY_BUSY', message: 'A preparation, review or Scenario Lab is in progress for this repository.' }, 409))
  renderSync()

  await user.click(screen.getByRole('button', { name: 'Check for new commits' }))
  expect(await screen.findByRole('status')).toHaveTextContent('Up to date: aaaaaaa is the latest commit on main.')
  await user.click(screen.getByRole('button', { name: 'Check for new commits' }))
  expect(await screen.findByText(/is in progress for this repository/)).toBeInTheDocument()
})
