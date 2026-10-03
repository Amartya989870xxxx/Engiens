import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth'
import { AuthPage } from './AuthPage'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

test('shows the server error when login fails', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ status: 401, code: 'INVALID_CREDENTIALS', message: 'Incorrect email or password', fieldErrors: {} }), {
      status: 401,
    }),
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter>
          <AuthPage mode="login" />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  await userEvent.type(screen.getByLabelText('Email'), 'a@b.com')
  await userEvent.type(screen.getByLabelText('Password'), 'password123')
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Incorrect email or password'))
})

test('an expired login sends the user back to log in with an explanation', async () => {
  localStorage.setItem('lens.token', 'expired-token')
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', message: 'Authentication required', fieldErrors: {} }), {
      status: 401,
    }),
  )
  const { api } = await import('../api')
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter>
          <AuthPage mode="login" />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  await expect(api('/api/profile')).rejects.toThrow()
  expect(localStorage.getItem('lens.token')).toBeNull()
  expect(await screen.findByRole('status')).toHaveTextContent('Your session expired')
})
