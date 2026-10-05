import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { AuthContext, type AuthState } from '../useAuth'
import { AppShell } from './AppShell'

afterEach(() => vi.restoreAllMocks())

const auth: AuthState = {
  user: { id: 'u1', name: 'Asha Rao', email: 'asha@example.com', profileCompleted: true },
  loading: false,
  sessionExpired: false,
  login: async () => {},
  register: async () => {},
  logout: () => {},
}

test('on phones the menu drawer opens, and navigating to another page closes it', async () => {
  const user = userEvent.setup()
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthContext.Provider value={auth}>
        <MemoryRouter initialEntries={['/dashboard']}>
          <Routes>
            <Route element={<AppShell />}>
              <Route path="/dashboard" element={<p>Dashboard</p>} />
              <Route path="/progress" element={<p>Progress page</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
  const drawer = screen.getByRole('complementary')
  expect(drawer.className).toContain('-translate-x-full')

  await user.click(screen.getByRole('button', { name: 'Open menu' }))
  expect(drawer.className).toContain(' translate-x-0')

  await user.click(screen.getByRole('link', { name: 'Progress' }))
  expect(await screen.findByText('Progress page')).toBeInTheDocument()
  expect(drawer.className).toContain('-translate-x-full')
})
