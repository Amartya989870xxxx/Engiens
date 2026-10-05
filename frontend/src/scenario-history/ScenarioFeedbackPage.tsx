import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ApiError, getScenarioFeedback } from '../api'
import { RevealScope } from '../shared/Reveal'
import { ScenarioReport } from './ScenarioReport'

/**
 * Feedback on one submitted scenario while the lab is still open. Read-only; the lab's full assessment
 * (overall picture and personal learning points) follows once every scenario is submitted.
 */
export function ScenarioFeedbackPage() {
  const { labId = '', scenarioId = '' } = useParams()
  const feedback = useQuery({
    queryKey: ['scenario-feedback', labId, scenarioId],
    queryFn: () => getScenarioFeedback(labId, scenarioId),
    retry: false,
  })

  return (
    <div className="mx-auto w-full max-w-4xl px-4 py-10 md:py-16">
      <Link to="/scenario-lab" className="font-mono text-xs text-muted transition-colors hover:text-ink">
        ← Back to the lab
      </Link>
      <p className="mt-6 text-xs uppercase tracking-[0.08em] text-muted">Scenario feedback</p>
      {feedback.isPending && <p className="mt-4 text-sm text-muted">Loading feedback…</p>}
      {feedback.error && (
        <h1 role="alert" className="mt-3 font-display text-3xl text-ink">
          {feedback.error instanceof ApiError ? feedback.error.message : 'Something went wrong. Please try again.'}
        </h1>
      )}
      {feedback.data && (
        <>
          <h1 className="sr-only">Feedback: {feedback.data.title}</h1>
          <RevealScope id={`feedback:${labId}:${scenarioId}`} className="mt-4">
            <ScenarioReport s={feedback.data} />
          </RevealScope>
          <p className="mt-12 border-t border-line pt-6 text-sm text-muted">
            Your overall assessment and personal learning points are written when you’ve submitted every scenario in this lab.
          </p>
        </>
      )}
    </div>
  )
}
