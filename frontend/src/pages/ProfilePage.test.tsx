import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth'
import { ProtectedRoute, RequireNoProfile } from '../components'
import { ProfilePage } from './ProfilePage'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
})

const json = (body: unknown) => new Response(JSON.stringify(body))
const profile = {
  name: 'Asha Rao', level: 'UNDERGRADUATE', classYear: 2, workExperience: null, languages: ['Java'], frameworks: ['Spring Boot'],
  databases: [], experienceAreas: ['Testing'], githubUsername: 'asha', goals: 'Write cleaner, production-quality code',
}

function mockServer() {
  const saved: unknown[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.endsWith('/api/auth/me')) return json({ id: '1', name: 'Asha Rao', email: 'a@b.com', profileCompleted: true })
    if (url.endsWith('/api/github/connection')) return json({ available: false, connected: false, login: null, manageUrl: null })
    if (url.endsWith('/api/profile') && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body))
      saved.push(body)
      return json({ ...profile, ...body, githubUsername: 'asha' })
    }
    if (url.endsWith('/api/profile')) return json(profile)
    throw new Error(`unexpected ${url}`)
  })
  return saved
}

function renderAt(url: string) {
  localStorage.setItem('lens.token', 't')
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={[url]}>
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route element={<RequireNoProfile />}>
                <Route path="/onboarding" element={<p>Onboarding</p>} />
              </Route>
              <Route path="/profile" element={<ProfilePage />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

test('onboarding is never shown again once a profile exists', async () => {
  mockServer()
  renderAt('/onboarding')
  expect(await screen.findByRole('heading', { name: 'Profile' })).toBeInTheDocument()
  expect(screen.queryByText('Onboarding')).not.toBeInTheDocument()
})

test('shows the profile and edits one section in place', async () => {
  const saved = mockServer()
  renderAt('/profile')

  expect(await screen.findByText('Undergraduate student · 2nd year')).toBeInTheDocument()
  expect(screen.getByText('@asha')).toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Edit Stack' }))
  // Other sections are locked while one is being edited.
  expect(screen.getByRole('button', { name: 'Edit About you' })).toBeDisabled()

  await userEvent.type(screen.getByRole('combobox', { name: 'Languages' }), 'Go{Enter}')
  await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

  expect(saved).toHaveLength(1)
  expect(saved[0]).toMatchObject({ languages: ['Java', 'Go'], name: 'Asha Rao', classYear: 2, githubUrl: 'https://github.com/asha' })
  const stack = (await screen.findByRole('heading', { name: 'Stack' })).closest('section')!
  expect(await within(stack).findByText('Go')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
})

test('cancel throws away unsaved changes', async () => {
  const saved = mockServer()
  renderAt('/profile')
  await userEvent.click(await screen.findByRole('button', { name: 'Edit About you' }))
  const name = screen.getByLabelText('Your name')
  await userEvent.clear(name)
  await userEvent.type(name, 'Someone Else')
  await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

  expect(screen.getByText('Asha Rao')).toBeInTheDocument()
  expect(saved).toHaveLength(0)
})
