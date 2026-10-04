import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, test, vi } from 'vitest'
import { runResult, scenarioDetail, STARTER } from './fixture'
import { ScenarioWorkspacePage } from './ScenarioWorkspacePage'

// jsdom can't lay out CodeMirror; a textarea stands in for it here (the real editor is checked in a browser).
vi.mock('./CodeEditor', () => ({
  CodeEditor: ({ value, onChange, label, readOnly }: { value: string; onChange: (v: string) => void; label: string; readOnly?: boolean }) => (
    <textarea aria-label={label} value={value} readOnly={readOnly} onChange={(e) => onChange(e.target.value)} />
  ),
}))

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
afterEach(() => vi.restoreAllMocks())

function renderWorkspace() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/scenario-lab/lab-1/scenarios/sc-1']}>
        <Routes>
          <Route path="/scenario-lab/:labId/scenarios/:scenarioId" element={<ScenarioWorkspacePage />} />
          <Route path="/scenario-lab" element={<p>Lab overview</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

type Call = { url: string; method: string; body: unknown }
function serve(detail = scenarioDetail()) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    if (url.endsWith('/run')) return json(runResult())
    if (url.endsWith('/submit')) return json({ id: 'att-1', scenarioId: 'sc-1', mode: 'CODE', runResult: runResult(), evaluationStatus: 'PENDING', errorCode: null, errorMessage: null, createdAt: '' })
    if (url.endsWith('/draft')) return new Response(null, { status: 204 })
    return json(detail)
  })
  return calls
}

test('the problem and the starter code are shown, in code mode, without hidden checks', async () => {
  serve()
  renderWorkspace()
  expect(await screen.findByRole('heading', { level: 1, name: 'Prevent duplicate orders under retries' })).toBeInTheDocument()
  expect(screen.getByText('Customers who double-click Pay see two identical orders.')).toBeInTheDocument()
  expect(screen.getByText('Scenario 1 of 5 · orders-api')).toBeInTheDocument()
  expect(screen.getByLabelText('orders.py')).toHaveValue(STARTER)
  expect(screen.getByRole('tab', { name: /store\.py/ })).toHaveTextContent('read-only')
  expect(screen.getByRole('button', { name: 'Switch to Approach' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Run checks' })).toBeInTheDocument()
})

test('switching to Approach and back keeps both the code and the reasoning, and autosaves them', async () => {
  const user = userEvent.setup()
  const calls = serve()
  renderWorkspace()

  const editor = await screen.findByLabelText('orders.py')
  await user.clear(editor)
  await user.type(editor, 'x = 1')
  await user.click(screen.getByRole('button', { name: 'Switch to Approach' }))
  await user.type(screen.getByRole('textbox', { name: 'Explain your approach' }), 'Use an idempotency key.')
  await user.click(screen.getByRole('button', { name: 'Switch to Code' }))
  expect(screen.getByLabelText('orders.py')).toHaveValue('x = 1')
  await user.click(screen.getByRole('button', { name: 'Switch to Approach' }))
  expect(screen.getByRole('textbox', { name: 'Explain your approach' })).toHaveValue('Use an idempotency key.')

  await waitFor(() => {
    const drafts = calls.filter((c) => c.url.endsWith('/draft'))
    expect(drafts.at(-1)?.body).toEqual({ mode: 'APPROACH', files: [{ path: 'orders.py', content: 'x = 1' }], approach: 'Use an idempotency key.' })
  })
})

test('Run shows objective results, check by check', async () => {
  const user = userEvent.setup()
  const calls = serve()
  renderWorkspace()

  await user.click(await screen.findByRole('button', { name: 'Run checks' }))
  expect(await screen.findByText('checks passing')).toHaveTextContent('2 / 3 checks passing')
  expect(screen.getByText('Failed:', { exact: false }).closest('li')).toHaveTextContent('ignores a repeated request')
  expect(screen.getByText('a repeated request created a second order')).toBeInTheDocument()
  expect(calls.find((c) => c.url.endsWith('/run'))?.body).toEqual({ files: [{ path: 'orders.py', content: STARTER }] }) // read-only files never sent
})

test('Submit asks for confirmation, sends the final answer and returns to the lab', async () => {
  const user = userEvent.setup()
  const calls = serve()
  renderWorkspace()

  await user.click(await screen.findByRole('button', { name: 'Submit' }))
  expect(screen.getByText(/final answer\? You can’t change it afterwards/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Submit final answer' }))

  expect(await screen.findByText('Lab overview')).toBeInTheDocument()
  expect(calls.find((c) => c.url.endsWith('/submit'))?.body).toEqual({ mode: 'CODE', files: [{ path: 'orders.py', content: STARTER }] })
})

test('an approach-only scenario has no editor, run or mode switch', async () => {
  serve(scenarioDetail({ executionCapability: 'APPROACH_ONLY', language: null, files: [], draftMode: 'APPROACH' }))
  renderWorkspace()
  expect(await screen.findByRole('textbox', { name: 'Explain your approach' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Switch to/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Run checks' })).not.toBeInTheDocument()
})

test('a submitted scenario is closed, not editable', async () => {
  serve(scenarioDetail({ submitted: true, evaluationStatus: 'PENDING' }))
  renderWorkspace()
  expect(await screen.findByText(/You’ve submitted this scenario/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Submit' })).not.toBeInTheDocument()
})
