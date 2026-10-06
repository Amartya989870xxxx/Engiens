import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth'
import { LandingPage } from './LandingPage'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

function renderLanding() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter>
          <LandingPage />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

test('visitors are offered sign-up and log-in', () => {
  renderLanding()
  expect(screen.getByRole('link', { name: 'Get started' })).toHaveAttribute('href', '/register')
  expect(screen.getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login')
  expect(screen.getByRole('link', { name: /Review your first repository/ })).toHaveAttribute('href', '/register')
})

test('signed-in users go straight to their dashboard', async () => {
  localStorage.setItem('lens.token', 't')
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ id: '1', name: 'Asha', email: 'a@b.com', profileCompleted: true })),
  )
  renderLanding()
  expect(await screen.findByRole('link', { name: 'Open dashboard' })).toHaveAttribute('href', '/dashboard')
  expect(screen.getByRole('link', { name: /Review your first repository/ })).toHaveAttribute('href', '/dashboard')
})

test('pointing at a review dimension explains what it covers', async () => {
  renderLanding()
  await userEvent.hover(screen.getByRole('button', { name: /Persistence/ }))
  expect(screen.getByText(/N\+1 queries/)).toBeInTheDocument()
})

test('the review index lists all sixteen rubric dimensions and the Scenario Lab is live, not "soon"', () => {
  renderLanding()
  const review = screen.getByRole('heading', { name: 'Review Index' }).closest('section')!
  expect(review.querySelectorAll('li')).toHaveLength(16)
  expect(screen.getByRole('heading', { name: 'Scenario Lab' })).toBeInTheDocument()
  expect(screen.queryByText(/^soon$/i)).not.toBeInTheDocument()
  expect(screen.queryByLabelText('Locked')).not.toBeInTheDocument()
})
