import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, getReviewHistory, startReview } from '../api'
import { Button } from '../ui'

const dateFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' })
const STATUS: Record<string, string> = { QUEUED: 'Queued', RUNNING: 'In progress', COMPLETED: 'Complete', FAILED: 'Failed' }

/** Start a review, open the latest one, or read past reviews of this repository. */
export function ReviewActions({ repositoryId }: { repositoryId: string }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const history = useQuery({ queryKey: ['review-history', repositoryId], queryFn: () => getReviewHistory(repositoryId) })
  const start = useMutation({
    mutationFn: (regenerate: boolean) => startReview(repositoryId, regenerate),
    onSuccess: async (run) => {
      await qc.invalidateQueries({ queryKey: ['review-history', repositoryId] })
      navigate(`/reviews/${run.id}`)
    },
  })
  const latest = history.data?.find((r) => r.status === 'COMPLETED')

  return (
    <section className="mt-12 border-t border-line pt-8" aria-labelledby="reviews">
      <h2 id="reviews" className="font-display text-2xl text-ink">
        Engineering review
      </h2>
      <p className="mt-1 max-w-2xl text-sm text-muted">
        An AI reviewer reads the prepared evidence and writes a review tuned to your profile: what you did well, what could
        improve, why it matters, and what to learn next. Usually one to three minutes.
      </p>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        {latest ? (
          <>
            <Button type="button" onClick={() => navigate(`/reviews/${latest.id}`)}>
              Open latest review
            </Button>
            <Button type="button" variant="secondary" busy={start.isPending} onClick={() => start.mutate(true)}>
              Review again
            </Button>
          </>
        ) : (
          <Button type="button" busy={start.isPending} onClick={() => start.mutate(false)}>
            Run review
          </Button>
        )}
      </div>
      {start.error && (
        <p role="alert" className="mt-3 text-sm text-danger">
          {start.error instanceof ApiError ? start.error.message : 'Something went wrong. Please try again.'}
        </p>
      )}
      {history.data && history.data.length > 0 && (
        <ul className="mt-8 divide-y divide-line border-y border-line text-sm" aria-label="Review history">
          {history.data.map((r) => (
            <li key={r.id}>
              <Link to={`/reviews/${r.id}`} className="flex items-center justify-between gap-4 py-2.5 text-muted hover:text-ink">
                <span>{dateFormat.format(new Date(r.createdAt))}</span>
                <span className="font-mono text-[12px]">{r.model ?? '—'}</span>
                <span className={r.status === 'FAILED' ? 'text-danger' : 'text-ink/80'}>{STATUS[r.status]}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
