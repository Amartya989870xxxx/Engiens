import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { AuthProvider } from './auth'
import { Layout, ProtectedRoute } from './components'
import { AppShell } from './shell/AppShell'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

test('logging out returns to the login page and forgets the token', async () => {
  localStorage.setItem('lens.token', 'valid-token')
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ id: '1', name: 'Asha', email: 'a@b.com', profileCompleted: true })),
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/dashboard']}>
          <Routes>
            <Route path="/login" element={<p>Login page</p>} />
            <Route element={<ProtectedRoute />}>
              <Route element={<Layout />}>
                <Route path="/dashboard" element={<p>Dashboard</p>} />
              </Route>
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  await userEvent.click(await screen.findByRole('button', { name: 'Log out' }))
  expect(await screen.findByText('Login page')).toBeInTheDocument()
  expect(localStorage.getItem('lens.token')).toBeNull()
})

test('the logo in the sidebar leads to the landing page', async () => {
  localStorage.setItem('lens.token', 'valid-token')
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ id: '1', name: 'Asha', email: 'a@b.com', profileCompleted: true })),
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/dashboard']}>
          <Routes>
            <Route path="/" element={<p>Landing page</p>} />
            <Route element={<ProtectedRoute />}>
              <Route element={<AppShell />}>
                <Route path="/dashboard" element={<p>Dashboard</p>} />
              </Route>
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  const logos = await screen.findAllByRole('link', { name: 'Engiens' })
  // Sidebar and phone top bar both show the logo; both must go home.
  expect(logos.map((l) => l.getAttribute('href'))).toEqual(['/', '/'])
  await userEvent.click(logos[0])
  expect(await screen.findByText('Landing page')).toBeInTheDocument()
})
