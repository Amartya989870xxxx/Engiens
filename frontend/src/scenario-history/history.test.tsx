import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { assessmentReport, historyItem } from '../scenario-lab/fixture'
import { LabAssessmentPage } from './LabAssessmentPage'
import { ScenarioFeedbackPage } from './ScenarioFeedbackPage'
import { ScenarioLabHistory } from './ScenarioLabHistory'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
afterEach(() => vi.restoreAllMocks())

function renderWith(path: string, element: React.ReactNode, route: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={route} element={element} />
          <Route path="*" element={<p>Elsewhere</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('the review ends with a CTA and a compact history; each lab opens its read-only assessment', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    json([historyItem({ id: 'lab-3', number: 3, roles: ['BACKEND_ENGINEER', 'CLOUD_ENGINEER'], scenarioCount: 10, scenariosCompleted: 10 }), historyItem()]),
  )
  renderWith('/reviews/rev-1', <ScenarioLabHistory repositoryId="repo-1" reviewId="rev-1" />, '/reviews/:id')

  expect(screen.getByRole('link', { name: 'Test yourself on this project' })).toHaveAttribute('href', '/scenario-lab?review=rev-1')
  const latest = await screen.findByRole('link', { name: /Lab #3/ })
  expect(latest).toHaveTextContent('Backend Engineer, Cloud Engineer · SDE2')
  expect(latest).toHaveTextContent('10 of 10 scenarios')
  expect(latest).toHaveAttribute('href', '/repositories/repo-1/scenario-labs/lab-3') // not the Scenario Lab workspace
  expect(screen.getByRole('link', { name: /Lab #1/ })).toBeInTheDocument()
})

test('a repository without labs says so', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(json([]))
  renderWith('/reviews/rev-1', <ScenarioLabHistory repositoryId="repo-1" reviewId="rev-1" />, '/reviews/:id')
  expect(await screen.findByText('No Scenario Lab attempts for this repository yet.')).toBeInTheDocument()
})

test('the assessment page is a read-only report with the evaluation, and exports a PDF', async () => {
  const user = userEvent.setup()
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
    String(input).endsWith('/pdf')
      ? new Response(new Blob(['%PDF-1.7']), { status: 200, headers: { 'Content-Disposition': 'attachment; filename="engiens-scenario-lab-orders-api-3.pdf"' } })
      : json(assessmentReport()),
  )
  URL.createObjectURL = vi.fn(() => 'blob:pdf')
  URL.revokeObjectURL = vi.fn()
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  renderWith('/repositories/repo-1/scenario-labs/lab-1', <LabAssessmentPage />, '/repositories/:repositoryId/scenario-labs/:labId')

  expect(await screen.findByRole('heading', { level: 1, name: 'orders-api' })).toBeInTheDocument()
  expect(screen.getByText('Scenario Lab assessment · Lab #3')).toBeInTheDocument()
  expect(screen.getByText('Correct fixes with gaps under concurrency.')).toBeInTheDocument()
  expect(screen.getByText('Concurrent requests can still race')).toBeInTheDocument()
  expect(screen.getByText('Order creation is not idempotent.')).toBeInTheDocument()
  expect(screen.getByText('Learn how unique constraints make retries safe')).toBeInTheDocument()
  expect(screen.getByText('3 / 3')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Run|Submit/ })).not.toBeInTheDocument() // never a workspace
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.getAllByRole('link', { name: /Back to/ })[0]).toHaveAttribute('href', '/reviews/rev-1')

  await user.click(screen.getAllByRole('button', { name: 'Export PDF' })[0])
  await waitFor(() => expect(click).toHaveBeenCalled())
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/api\/scenario-labs\/lab-1\/assessment\/pdf$/), expect.anything())
})

test('an assessment that isn’t yours or doesn’t exist shows a clear message', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    json({ status: 404, code: 'SCENARIO_ASSESSMENT_NOT_FOUND', message: 'We couldn’t find that assessment.', fieldErrors: {} }, 404),
  )
  renderWith('/repositories/repo-1/scenario-labs/nope', <LabAssessmentPage />, '/repositories/:repositoryId/scenario-labs/:labId')
  expect(await screen.findByRole('heading', { name: 'We couldn’t find that assessment.' })).toBeInTheDocument()
})

test('one scenario’s feedback is readable while the lab is still open, read-only', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(assessmentReport().scenarios[0]))
  renderWith('/scenario-lab/lab-1/scenarios/sc-1/feedback', <ScenarioFeedbackPage />, '/scenario-lab/:labId/scenarios/:scenarioId/feedback')

  expect(await screen.findByRole('heading', { level: 3, name: 'Prevent duplicate orders under retries' })).toBeInTheDocument()
  expect(screen.getByText('Concurrent requests can still race')).toBeInTheDocument()
  expect(screen.getByText('Order creation is not idempotent.')).toBeInTheDocument()
  expect(screen.getByText(/overall assessment and personal learning points are written when/)).toBeInTheDocument()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '← Back to the lab' })).toHaveAttribute('href', '/scenario-lab')
  expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/api\/scenario-labs\/lab-1\/scenarios\/sc-1\/feedback$/), expect.anything())
})

test('feedback that isn’t ready yet says so', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    json({ status: 409, code: 'SCENARIO_FEEDBACK_NOT_READY', message: 'This scenario’s evaluation isn’t ready yet.', fieldErrors: {} }, 409),
  )
  renderWith('/scenario-lab/lab-1/scenarios/sc-1/feedback', <ScenarioFeedbackPage />, '/scenario-lab/:labId/scenarios/:scenarioId/feedback')
  expect(await screen.findByRole('alert')).toHaveTextContent('This scenario’s evaluation isn’t ready yet.')
})
