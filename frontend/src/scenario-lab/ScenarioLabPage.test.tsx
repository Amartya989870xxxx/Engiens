import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { reviewRun } from '../review/fixture'
import { lab, scenarioSummary } from './fixture'
import { LabCompletePage } from './LabCompletePage'
import { ScenarioLabPage } from './ScenarioLabPage'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const empty = () => new Response(null, { status: 204 })

beforeEach(() => sessionStorage.clear())
afterEach(() => vi.restoreAllMocks())

function renderAt(path: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/scenario-lab" element={<ScenarioLabPage />} />
          <Route path="/scenario-lab/complete/:labId" element={<LabCompletePage />} />
          <Route path="/scenario-lab/:labId/scenarios/:scenarioId" element={<p>Workspace</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Routes fetches by URL; `post` answers POST /api/scenario-labs. */
function serve(active: () => Response, extra: Record<string, () => Response> = {}, post?: (body: unknown) => Response) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST' && url.endsWith('/api/scenario-labs') && post) return post(JSON.parse(String(init.body)))
    if (url.endsWith('/api/scenario-labs/active')) return active()
    for (const [suffix, answer] of Object.entries(extra)) if (url.endsWith(suffix)) return answer()
    return json([])
  })
}

test('with no open lab, Scenario Lab is an empty start screen without any history', async () => {
  serve(empty)
  renderAt('/scenario-lab')

  expect(await screen.findByRole('heading', { name: 'Test your engineering judgement against a real codebase.' })).toBeInTheDocument()
  expect(screen.getByLabelText('GitHub repository link')).toBeInTheDocument()
  expect(screen.queryByText(/history/i)).not.toBeInTheDocument()
  expect(screen.getByRole('radio', { name: /^5/ })).toBeChecked() // 5 is the default and recommended
  expect(screen.getByText('Recommended')).toBeInTheDocument()
})

test('roles and seniority are separate choices, and the request carries exactly what was chosen', async () => {
  const user = userEvent.setup()
  let sent: unknown
  serve(empty, {}, (body) => {
    sent = body
    return json(lab({ status: 'GENERATING', scenariosReady: 0, scenarios: [] }))
  })
  renderAt('/scenario-lab')

  await user.type(await screen.findByLabelText('GitHub repository link'), 'https://github.com/asha/orders-api')
  const roles = screen.getByRole('combobox', { name: 'Roles' })
  await user.type(roles, 'Backend')
  await user.keyboard('{Enter}')
  await user.type(roles, 'Cloud')
  await user.keyboard('{Enter}')
  await user.type(roles, 'Wizard')
  expect(screen.queryByRole('option', { name: /Add/ })).not.toBeInTheDocument() // only listed roles can be chosen
  await user.clear(roles)
  await user.click(screen.getByRole('radio', { name: /SDE2/ }))
  await user.click(screen.getByRole('radio', { name: /^10/ }))
  await user.click(screen.getByRole('button', { name: 'Generate Scenario Lab' }))

  expect(sent).toEqual({
    repositoryUrl: 'https://github.com/asha/orders-api',
    roles: ['BACKEND_ENGINEER', 'CLOUD_ENGINEER'],
    seniority: 'SDE2',
    scenarioCount: 10,
  })
})

test('a lab can’t start without a role, and Broad Engineering replaces specific roles', async () => {
  const user = userEvent.setup()
  const fetch = serve(empty, {}, () => json(lab()))
  renderAt('/scenario-lab')

  await user.type(await screen.findByLabelText('GitHub repository link'), 'https://github.com/asha/orders-api')
  await user.click(screen.getByRole('button', { name: 'Generate Scenario Lab' }))
  expect(screen.getByText('Choose at least one role')).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalledWith(expect.stringMatching(/\/api\/scenario-labs$/), expect.anything())

  const roles = screen.getByRole('combobox', { name: 'Roles' })
  await user.type(roles, 'Backend')
  await user.keyboard('{Enter}')
  await user.type(roles, 'Broad')
  await user.keyboard('{Enter}')
  expect(screen.getByRole('button', { name: 'Remove All / Broad Engineering' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Remove Backend Engineer' })).not.toBeInTheDocument()
})

test('coming from a review, the repository and snapshot carry over without pasting anything', async () => {
  const user = userEvent.setup()
  let sent: Record<string, unknown> = {}
  serve(empty, { '/api/reviews/rev-1': () => json(reviewRun()) }, (body) => {
    sent = body as Record<string, unknown>
    return json(lab({ status: 'GENERATING', scenariosReady: 0, scenarios: [] }))
  })
  renderAt('/scenario-lab?review=rev-1')

  expect(await screen.findByText('orders-api')).toBeInTheDocument()
  expect(screen.getByText(/From your review/)).toBeInTheDocument()
  expect(screen.queryByLabelText('GitHub repository link')).not.toBeInTheDocument()
  await user.type(screen.getByRole('combobox', { name: 'Roles' }), 'Backend')
  await user.keyboard('{Enter}')
  await user.click(screen.getByRole('button', { name: 'Generate Scenario Lab' }))
  expect(sent.reviewId).toBe('rev-1')
  expect(sent.repositoryUrl).toBeUndefined()
})

test('generation shows honest progress', async () => {
  serve(() => json(lab({ status: 'GENERATING', scenariosReady: 2, scenarios: [] })))
  renderAt('/scenario-lab')
  expect(await screen.findByRole('status')).toHaveTextContent('2 of 5 ready')
  expect(screen.getByRole('button', { name: 'Stop generating' })).toBeInTheDocument()
})

test('an open lab lists its scenarios and where each one stands', async () => {
  serve(() =>
    json(
      lab({
        scenarios: [
          scenarioSummary(),
          scenarioSummary({ id: 'sc-2', position: 2, title: 'Add a timeout to payments', submitted: true, evaluationStatus: 'PENDING' }),
          scenarioSummary({ id: 'sc-3', position: 3, title: 'Split the order service', submitted: true, evaluationStatus: 'FAILED', executionCapability: 'APPROACH_ONLY', language: null }),
          scenarioSummary({ id: 'sc-4', position: 4, title: 'Cache the catalogue', submitted: true, evaluationStatus: 'COMPLETED' }),
          scenarioSummary({ id: 'sc-5', position: 5, title: 'Add a health check' }),
        ],
      }),
    ),
  )
  renderAt('/scenario-lab')

  expect(await screen.findByRole('link', { name: /Prevent duplicate orders under retries/ })).toHaveAttribute('href', '/scenario-lab/lab-1/scenarios/sc-1')
  expect(screen.queryByRole('link', { name: /Add a timeout to payments/ })).not.toBeInTheDocument() // submitted: no workspace
  expect(screen.getByText('Evaluating…')).toBeInTheDocument()
  expect(screen.getByText('Evaluation failed')).toBeInTheDocument()
  expect(screen.getByText('3 of 5 submitted')).toBeInTheDocument()
  // An evaluated scenario's feedback can be read straight away, without finishing the lab.
  expect(screen.getByRole('link', { name: 'View feedback' })).toHaveAttribute('href', '/scenario-lab/lab-1/scenarios/sc-4/feedback')
})

test('when the open lab completes, the user lands on the completion page, then Scenario Lab is empty again', async () => {
  let open = true
  serve(() => (open ? json(lab()) : empty()), { '/api/scenario-labs/lab-1': () => json(lab({ status: 'COMPLETED', completedAt: '2026-10-04T12:00:00Z' })) })
  renderAt('/scenario-lab')
  await screen.findByText('Scenarios')
  cleanup()

  // The lab finishes in the background; the next visit notices and shows how it ended, once.
  open = false
  renderAt('/scenario-lab')
  expect(await screen.findByRole('heading', { name: 'Scenario Lab complete.' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'View assessment' })).toHaveAttribute('href', '/repositories/repo-1/scenario-labs/lab-1')
  expect(screen.getByRole('link', { name: 'Back to review' })).toHaveAttribute('href', '/reviews/rev-1')

  cleanup()
  renderAt('/scenario-lab')
  expect(await screen.findByRole('heading', { name: 'Test your engineering judgement against a real codebase.' })).toBeInTheDocument()
})

test('with some scenarios submitted, the lab can be finished early after confirming', async () => {
  const user = userEvent.setup()
  const calls: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST') calls.push(url)
    if (url.endsWith('/finish')) return new Response(null, { status: 202 })
    return json(lab({ scenarios: [scenarioSummary({ submitted: true, evaluationStatus: 'COMPLETED' }), ...[2, 3, 4, 5].map((n) => scenarioSummary({ id: `sc-${n}`, position: n, title: `Scenario ${n}` }))] }))
  })
  renderAt('/scenario-lab')

  await user.click(await screen.findByRole('button', { name: 'Finish lab now' }))
  expect(screen.getByRole('group', { name: 'Confirm finishing the lab' })).toHaveTextContent('Finish with 1 of 5 submitted? The 4 unsubmitted scenarios won’t be assessed.')
  await user.click(screen.getByRole('button', { name: 'Finish lab' }))
  expect(calls).toEqual([expect.stringMatching(/\/api\/scenario-labs\/lab-1\/finish$/)])
})

test('a lab can’t be finished while an answer is still being evaluated', async () => {
  serve(() => json(lab({ scenarios: [scenarioSummary({ submitted: true, evaluationStatus: 'PENDING' }), ...[2, 3, 4, 5].map((n) => scenarioSummary({ id: `sc-${n}`, position: n, title: `Scenario ${n}` }))] })))
  renderAt('/scenario-lab')
  expect(await screen.findByRole('button', { name: 'Finish lab now' })).toBeDisabled()
  expect(screen.getByText('Wait for your answers to be evaluated before finishing.')).toBeInTheDocument()
})

test('while generating, ready scenarios can be opened, the rest are visibly not ready, and finishing waits', async () => {
  const ready = [1, 2, 3].map((n) => scenarioSummary({ id: `sc-${n}`, position: n, title: `Ready scenario ${n}` }))
  serve(() => json(lab({ status: 'GENERATING', generationStage: 'BUILDING', scenarioCount: 20, scenariosReady: 3, scenariosRejected: 2, scenarios: ready })))
  renderAt('/scenario-lab')

  expect(await screen.findByRole('status')).toHaveTextContent('3 of 20 ready · 2 replaced after validation')
  expect(screen.getByRole('link', { name: /Ready scenario 1/ })).toHaveAttribute('href', '/scenario-lab/lab-1/scenarios/sc-1')
  expect(screen.getAllByText('Generating…')).toHaveLength(17)
  expect(screen.getAllByText('Generating…')[0].closest('li')).toHaveAttribute('aria-disabled')
  expect(screen.getAllByRole('link', { name: /scenario/i })).toHaveLength(3) // placeholders are not selectable
  expect(screen.queryByRole('button', { name: 'Finish lab now' })).not.toBeInTheDocument()
  expect(screen.getByText('You can finish the lab once generation has ended.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Stop generating' })).toBeInTheDocument()
})

test('while the plan is being written, progress says so without inventing numbers', async () => {
  serve(() => json(lab({ status: 'GENERATING', generationStage: 'PLANNING', scenarioCount: 10, scenariosReady: 0, scenarios: [] })))
  renderAt('/scenario-lab')
  expect(await screen.findByRole('status')).toHaveTextContent('Reading the repository and planning scenarios from its code…')
  expect(screen.getAllByText('Generating…')).toHaveLength(10)
})

test('a lab whose generation ended partially says how many scenarios exist', async () => {
  const some = Array.from({ length: 17 }, (_, i) => scenarioSummary({ id: `sc-${i + 1}`, position: i + 1, title: `Scenario ${i + 1}` }))
  serve(() => json(lab({ scenarioCount: 20, scenariosReady: 17, generationNote: '17 of 20 scenarios generated.', scenarios: some })))
  renderAt('/scenario-lab')
  expect(await screen.findByRole('note')).toHaveTextContent('17 of 20 scenarios generated. Only these can be solved and assessed.')
  expect(screen.getByText('0 of 17 submitted')).toBeInTheDocument()
  expect(screen.queryByText('Generating…')).not.toBeInTheDocument()
})

test('setup warns honestly when larger labs would run on fallback models', async () => {
  const user = userEvent.setup()
  serve(empty, { '/api/scenario-labs/capacity': () => json({ preferredModelAvailable: false }) })
  renderAt('/scenario-lab')
  await user.click(await screen.findByRole('radio', { name: /^10/ }))
  expect(await screen.findByRole('note')).toHaveTextContent('a 10-scenario lab will run on fallback models')
  await user.click(screen.getByRole('radio', { name: /^5/ }))
  expect(screen.queryByRole('note')).not.toBeInTheDocument()
})

test('setup says so when this server cannot run code, so labs will be answered in writing', async () => {
  serve(empty, { '/api/scenario-labs/capacity': () => json({ preferredModelAvailable: true, codeExecutionAvailable: false }) })
  renderAt('/scenario-lab')
  expect(await screen.findByRole('note')).toHaveTextContent('Code can’t be run on this server (it has no code sandbox)')
})

test('no sandbox note when code execution is available', async () => {
  serve(empty, { '/api/scenario-labs/capacity': () => json({ preferredModelAvailable: true, codeExecutionAvailable: true }) })
  renderAt('/scenario-lab')
  await screen.findByRole('heading', { name: /Test your engineering judgement/ })
  expect(screen.queryByRole('note')).not.toBeInTheDocument()
})
