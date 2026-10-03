import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth'
import { OnboardingPage } from './OnboardingPage'

beforeEach(() => {
  // No saved profile yet; auth isn't needed for these cards.
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ status: 404, code: 'PROFILE_NOT_FOUND', message: 'x', fieldErrors: {} }), { status: 404 }),
  )
})

afterEach(() => {
  vi.restoreAllMocks()
  sessionStorage.clear()
})

function renderPage(url = '/onboarding') {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={[url]}>
          <OnboardingPage />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

const yearOptions = () =>
  within(screen.getByLabelText('Year of study'))
    .getAllByRole('option')
    .filter((o) => !(o as HTMLOptionElement).disabled)
    .map((o) => o.textContent)

test('year of study depends on the chosen status', async () => {
  renderPage()
  const status = await screen.findByLabelText('Current status')
  expect(screen.queryByLabelText('Year of study')).not.toBeInTheDocument()

  await userEvent.selectOptions(status, 'UNDERGRADUATE')
  expect(yearOptions()).toEqual(['1st year', '2nd year', '3rd year', '4th year'])

  await userEvent.selectOptions(status, 'GRADUATE')
  expect(yearOptions()).toEqual(['1st year', '2nd year'])

  await userEvent.selectOptions(status, 'PROFESSIONAL')
  expect(screen.queryByLabelText('Year of study')).not.toBeInTheDocument()
  expect(screen.getByLabelText('Years of experience')).toBeInTheDocument()
})

test('moves to the stack card only once the first card is complete', async () => {
  renderPage()
  await userEvent.type(await screen.findByLabelText('Your name'), 'Asha')
  await userEvent.selectOptions(screen.getByLabelText('Current status'), 'UNDERGRADUATE')
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
  expect(screen.getByRole('alert')).toHaveTextContent('Choose your year of study.')

  await userEvent.selectOptions(screen.getByLabelText('Year of study'), '2')
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
  expect(await screen.findByRole('heading', { name: 'What do you build with?' })).toBeInTheDocument()
})

test('languages can be picked from the list or typed in', async () => {
  renderPage()
  await userEvent.type(await screen.findByLabelText('Your name'), 'Asha')
  await userEvent.selectOptions(screen.getByLabelText('Current status'), 'PROFESSIONAL')
  await userEvent.selectOptions(screen.getByLabelText('Years of experience'), 'ONE_TO_TWO_YEARS')
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

  const languages = await screen.findByRole('combobox', { name: 'Languages' })
  await userEvent.type(languages, 'typesc')
  await userEvent.click(screen.getByRole('option', { name: 'TypeScript' }))
  await userEvent.type(languages, 'Gleam{Enter}')

  expect(screen.getByRole('button', { name: 'Remove TypeScript' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Remove Gleam' })).toBeInTheDocument()
})

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

test('returning from GitHub restores answers and shows the shared repositories', async () => {
  sessionStorage.setItem(
    'lens.onboarding',
    JSON.stringify({
      step: 2,
      savedAt: Date.now(),
      draft: {
        name: 'Asha', level: 'PROFESSIONAL', classYear: null, workExperience: 'ONE_TO_TWO_YEARS', languages: ['Go'],
        frameworks: [], databases: [], experienceAreas: ['Testing'], githubUrl: '', githubAccess: 'private',
        goalChoice: null, customGoal: '',
      },
    }),
  )
  vi.mocked(fetch).mockImplementation(async (input) => {
    const url = String(input)
    if (url.endsWith('/api/github/connection'))
      return json({ available: true, connected: true, login: 'asha', manageUrl: 'https://github.com/settings/installations/7' })
    if (url.endsWith('/api/github/connection/repositories'))
      return json([{ name: 'secret-app', description: null, language: 'Go', stars: 0, privateRepo: true, htmlUrl: 'https://github.com/asha/secret-app', pushedAt: null }])
    return json({ status: 404, code: 'PROFILE_NOT_FOUND', message: 'x', fieldErrors: {} }, 404)
  })

  renderPage('/onboarding?github=connected')

  expect(await screen.findByRole('heading', { name: 'What have you worked on?' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Remove Testing' })).toBeInTheDocument()
  expect(await screen.findByText('secret-app')).toBeInTheDocument()
  expect(screen.getByText('Private')).toBeInTheDocument()
  expect(screen.getByText('@asha')).toBeInTheDocument()
})

test('explains a failed connection when GitHub sends the user back', async () => {
  vi.mocked(fetch).mockImplementation(async (input) =>
    String(input).endsWith('/api/github/connection')
      ? json({ available: true, connected: false, login: null, manageUrl: null })
      : json({ status: 404, code: 'PROFILE_NOT_FOUND', message: 'x', fieldErrors: {} }, 404),
  )
  renderPage('/onboarding?github=GITHUB_STATE_INVALID')
  expect(await screen.findByRole('alert')).toHaveTextContent('That GitHub link expired')
  expect(screen.getByRole('button', { name: 'Connect GitHub' })).toBeInTheDocument()
})
