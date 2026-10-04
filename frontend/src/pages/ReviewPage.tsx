import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ApiError, getReview, startReview, type ReviewRun } from '../api'
import { ReviewReport } from '../review/ReviewReport'
import { Button } from '../ui'

const dateFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' })

/** An engineering review: progress while it runs, then the report. */
export function ReviewPage() {
  const { id = '' } = useParams()
  const run = useQuery({
    queryKey: ['review', id],
    queryFn: () => getReview(id),
    // A review takes a minute or more; check back until it finishes.
    refetchInterval: (q) => (q.state.data && (q.state.data.status === 'QUEUED' || q.state.data.status === 'RUNNING') ? 3000 : false),
    retry: (failures, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failures < 2,
  })

  return (
    <div className="mx-auto w-full max-w-4xl px-4 py-10 md:py-16">
      {run.isPending && <p className="text-sm text-muted">Loading review…</p>}
      {run.error && (
        <div role="alert">
          <h1 className="font-display text-3xl text-ink">
            {run.error instanceof ApiError ? run.error.message : 'Something went wrong. Please try again.'}
          </h1>
          <Link to="/dashboard" className="mt-4 inline-block text-sm text-muted underline underline-offset-4 hover:text-ink">
            Back to the dashboard
          </Link>
        </div>
      )}
      {run.data && <ReviewView run={run.data} />}
    </div>
  )
}

function ReviewView({ run }: { run: ReviewRun }) {
  const repoName = run.repositoryName ?? 'Repository'
  return (
    <article>
      <Link to={`/repositories/${run.repositoryId}`} className="font-mono text-xs text-muted transition-colors hover:text-ink">
        ← {repoName}
      </Link>
      <p className="mt-6 text-xs uppercase tracking-[0.08em] text-muted">Engiens review</p>
      <h1 className="mt-2 break-words font-display text-[2.75rem] leading-[1.05] tracking-[-0.015em] text-ink sm:text-6xl">
        {repoName}
      </h1>
      <p className="mt-4 flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted">
        {run.commitSha && (
          <span>
            Commit <span className="font-mono text-[13px] text-ink/80">{run.commitSha.slice(0, 7)}</span>
          </span>
        )}
        {run.completedAt && <span>Reviewed {dateFormat.format(new Date(run.completedAt))}</span>}
        {run.model && <span>Reviewed with {run.model}</span>}
      </p>

      <div className="mt-12">
        {(run.status === 'QUEUED' || run.status === 'RUNNING') && <InProgress />}
        {run.status === 'FAILED' && <Failed run={run} />}
        {run.status === 'COMPLETED' && run.review && <ReviewReport reviewId={run.id} review={run.review} />}
      </div>
    </article>
  )
}

function InProgress() {
  return (
    <div role="status" className="border-t border-line pt-8">
      <p className="flex items-center gap-2.5 text-ink">
        <span aria-hidden className="size-1.5 animate-pulse rounded-full bg-ink motion-reduce:animate-none" />
        Reviewing the prepared evidence…
      </p>
      <p className="mt-3 max-w-xl text-sm leading-relaxed text-muted">
        A senior-engineer-style review of your code takes one to three minutes. You can leave this page; the review will be
        waiting here and under Recent reviews.
      </p>
    </div>
  )
}

function Failed({ run }: { run: ReviewRun }) {
  const navigate = useNavigate()
  const retry = useMutation({
    mutationFn: () => startReview(run.repositoryId),
    onSuccess: (next) => navigate(`/reviews/${next.id}`),
  })
  return (
    <div className="space-y-4 border-t border-line pt-8">
      <p role="alert" className="flex items-center gap-2.5 text-ink">
        <span aria-hidden className="size-1.5 shrink-0 rounded-full bg-danger" />
        {run.errorMessage ?? 'The review failed.'}
      </p>
      {retry.error && (
        <p className="text-sm text-danger">{retry.error instanceof ApiError ? retry.error.message : 'Please try again.'}</p>
      )}
      <Button type="button" variant="secondary" busy={retry.isPending} onClick={() => retry.mutate()}>
        Try again
      </Button>
    </div>
  )
}
