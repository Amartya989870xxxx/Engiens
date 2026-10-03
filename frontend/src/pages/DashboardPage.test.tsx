import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'

afterEach(() => vi.restoreAllMocks())

const json = (body: unknown) => new Response(JSON.stringify(body))
const profile = {
  name: 'Asha', level: 'UNDERGRADUATE', classYear: 2, workExperience: null, languages: ['Java'], frameworks: [],
  databases: [], experienceAreas: [], githubUsername: 'asha', goals: null,
}

test('suggests the user’s own repositories and fills the input when one is picked', async () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    if (url.endsWith('/api/profile')) return json(profile)
    if (url.endsWith('/api/github/connection')) return json({ available: false, connected: false, login: null, manageUrl: null })
    if (url.includes('/api/github/projects'))
      return json({
        username: 'asha',
        projects: [
          { name: 'old-app', description: null, language: 'Go', stars: 0, privateRepo: false, htmlUrl: 'https://github.com/asha/old-app', pushedAt: '2025-01-01T00:00:00Z' },
          { name: 'lens', description: null, language: 'Java', stars: 2, privateRepo: false, htmlUrl: 'https://github.com/asha/lens', pushedAt: '2026-09-01T00:00:00Z' },
        ],
      })
    throw new Error(`unexpected ${url}`)
  })
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )

  // Most recently pushed repository comes first.
  const picks = await screen.findAllByRole('button', { name: /^Review / })
  expect(picks.map((b) => b.textContent)).toEqual(['Review lensJava', 'Review old-appGo'])
  expect(screen.getByText(/Feedback tuned for undergraduate student · 2nd year · Java/)).toBeInTheDocument()

  await userEvent.click(picks[0])
  expect(screen.getByLabelText('GitHub repository URL')).toHaveValue('https://github.com/asha/lens')
})
