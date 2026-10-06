import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'

afterEach(() => vi.restoreAllMocks())

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const profile = {
  name: 'Asha', level: 'UNDERGRADUATE', classYear: 2, workExperience: null, languages: ['Java'], frameworks: [],
  databases: [], experienceAreas: [], githubUsername: 'asha', goals: null,
}
const projects = {
  username: 'asha',
  projects: [
    { name: 'old-app', description: null, language: 'Go', stars: 0, privateRepo: false, htmlUrl: 'https://github.com/asha/old-app', pushedAt: '2025-01-01T00:00:00Z' },
    { name: 'lens', description: null, language: 'Java', stars: 2, privateRepo: false, htmlUrl: 'https://github.com/asha/lens', pushedAt: '2026-09-01T00:00:00Z' },
  ],
}

/**
 * Fakes the backend. `onImport` decides the import response; it receives the posted URL and
 * returns a Response (or a promise of one, to hold the request open).
 */
function mockServer(onImport: (url: string) => Response | Promise<Response>) {
  const imported: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.endsWith('/api/repositories/import')) {
      const body = JSON.parse(String(init?.body))
      imported.push(body.url)
      return onImport(body.url)
    }
    if (url.endsWith('/api/profile')) return json(profile)
    if (url.endsWith('/api/github/connection')) return json({ available: false, connected: false, login: null, manageUrl: null })
    if (url.includes('/api/github/projects')) return json(projects)
    throw new Error(`unexpected ${url}`)
  })
  return imported
}

function Overview() {
  return <p>Overview of {useParams().id}</p>
}

function renderDashboard() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/repositories/:id" element={<Overview />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('suggests the user’s newest repositories first, with the personalisation line', async () => {
  mockServer(() => json({}))
  renderDashboard()
  const picks = await screen.findAllByRole('button', { name: /^Review / })
  expect(picks.map((b) => b.textContent)).toEqual(['Review lensJava', 'Review old-appGo'])
  expect(screen.getByText(/Feedback tuned for undergraduate student · 2nd year · Java/)).toBeInTheDocument()
})

test('clicking a repository imports it, shows progress, then opens its overview', async () => {
  let finish!: (r: Response) => void
  const imported = mockServer(() => new Promise<Response>((resolve) => (finish = resolve)))
  renderDashboard()

  await userEvent.click(await screen.findByRole('button', { name: /Review lens/ }))

  expect(imported).toEqual(['https://github.com/asha/lens'])
  expect(await screen.findByRole('button', { name: /Importing lens…/ })).toBeDisabled()
  // Other rows can't start a second import meanwhile.
  expect(screen.getByRole('button', { name: /Review old-app/ })).toBeDisabled()

  finish(json({ id: 'repo-1', status: 'READY' }))
  expect(await screen.findByText('Overview of repo-1')).toBeInTheDocument()
})

test('a pasted repository link is imported the same way', async () => {
  const imported = mockServer(() => json({ id: 'repo-2', status: 'READY' }))
  renderDashboard()
  await userEvent.type(await screen.findByLabelText('GitHub repository URL'), 'https://github.com/octocat/Hello-World{Enter}')
  expect(imported).toEqual(['https://github.com/octocat/Hello-World'])
  expect(await screen.findByText('Overview of repo-2')).toBeInTheDocument()
})

test('import errors are shown in the server’s own words', async () => {
  mockServer(() =>
    json({ status: 403, code: 'REPOSITORY_NO_ACCESS', message: 'You don’t have access to this repository through your connected GitHub account.', fieldErrors: {} }, 403),
  )
  renderDashboard()
  await userEvent.click(await screen.findByRole('button', { name: /Review lens/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('You don’t have access to this repository')
  // The row is usable again for another attempt.
  expect(screen.getByRole('button', { name: /Review lens/ })).toBeEnabled()
})

test('the Scenario Lab is offered as a working link', async () => {
  mockServer(() => json({}))
  renderDashboard()
  expect(await screen.findByRole('link', { name: /Practice in the Scenario Lab/ })).toHaveAttribute('href', '/scenario-lab')
})
