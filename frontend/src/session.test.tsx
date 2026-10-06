import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { api, SESSION_EXPIRED_EVENT, tokenStore } from './api'
import { AuthProvider } from './auth'
import { ProtectedRoute } from './components'
import { useAuth } from './useAuth'

/*
 * Leaving the tab and coming back: TanStack Query refetches every visible query on window focus, so the first
 * request after an idle period carries the saved token. Only a 401 for that token may end the session.
 */

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  focusManager.setFocused(undefined)
})

const me = { id: '1', name: 'Asha', email: 'a@b.com', profileCompleted: true }

function LoginProbe() {
  const { sessionExpired } = useAuth()
  return <p>Login page{sessionExpired && ': session expired'}</p>
}

function renderSignedIn() {
  localStorage.setItem('lens.token', 'saved-token')
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/dashboard']}>
          <Routes>
            <Route path="/login" element={<LoginProbe />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/dashboard" element={<p>Dashboard</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** The user switches away from the tab, then comes back. */
function leaveAndReturn() {
  act(() => focusManager.setFocused(false))
  act(() => focusManager.setFocused(true))
}

const authHeader = (call: unknown[]) => new Headers((call[1] as RequestInit | undefined)?.headers).get('Authorization')

test('returning to the tab re-checks the saved token and keeps the user signed in while it is valid', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify(me)))
  renderSignedIn()
  expect(await screen.findByText('Dashboard')).toBeInTheDocument()
  const before = fetch.mock.calls.length

  leaveAndReturn()

  await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(before))
  expect(authHeader(fetch.mock.calls.at(-1)!)).toBe('Bearer saved-token')
  expect(screen.getByText('Dashboard')).toBeInTheDocument()
  expect(localStorage.getItem('lens.token')).toBe('saved-token')
})

test('a server that is unreachable or restarting when the user returns does not end the session', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify(me)))
  renderSignedIn()
  expect(await screen.findByText('Dashboard')).toBeInTheDocument()
  fetch.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValue(
    new Response(JSON.stringify({ status: 503, code: 'UNAVAILABLE', message: 'Starting', fieldErrors: {} }), { status: 503 }),
  )

  leaveAndReturn() // the server can't be reached
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  leaveAndReturn() // the server is still starting
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3))
  expect(screen.getByText('Dashboard')).toBeInTheDocument()
  expect(localStorage.getItem('lens.token')).toBe('saved-token')
})

test('only a 401 for the saved token on return ends the session, with the expired message', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify(me)))
  renderSignedIn()
  expect(await screen.findByText('Dashboard')).toBeInTheDocument()
  fetch.mockResolvedValue(
    new Response(JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', message: 'Authentication required', fieldErrors: {} }), {
      status: 401,
    }),
  )

  leaveAndReturn()

  expect(await screen.findByText('Login page: session expired')).toBeInTheDocument()
  expect(localStorage.getItem('lens.token')).toBeNull()
})

test('a late 401 for a request sent with an older token does not end a newer login', async () => {
  tokenStore.set('old-token')
  let answer!: (r: Response) => void
  vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise((resolve) => (answer = resolve)))
  const expired = vi.fn()
  window.addEventListener(SESSION_EXPIRED_EVENT, expired)

  const pending = api('/api/progress').catch((e: unknown) => e) // sent with the old token
  tokenStore.set('new-token') // meanwhile the user logs in again
  answer(new Response(JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', message: 'Authentication required' }), { status: 401 }))

  expect(await pending).toMatchObject({ status: 401 }) // the old request still fails
  expect(tokenStore.get()).toBe('new-token')
  expect(expired).not.toHaveBeenCalled()
  window.removeEventListener(SESSION_EXPIRED_EVENT, expired)
})
