import { Link } from 'react-router-dom'
import { useInfiniteQuery } from '@tanstack/react-query'
import { getProgressHistory, type ProgressHistoryItem } from '../api'
import { AssessmentMark } from '../review/AssessmentMark'
import { rolesLabel, SENIORITY_LABELS } from '../scenario-lab/options'
import { Button, ErrorBanner } from '../ui'
import { formatDate, shortCommit } from './labels'

/** Completed reviews and labs, newest first, across repositories or in the chosen one. */
export function ProgressHistory({ repositoryId }: { repositoryId: string | null }) {
  const history = useInfiniteQuery({
    queryKey: ['progress-history', repositoryId],
    queryFn: ({ pageParam }) => getProgressHistory(repositoryId, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
  })
  if (history.isPending) return <p className="text-sm text-muted">Loading history…</p>
  if (history.error) return <ErrorBanner error={history.error} />
  const items = history.data.pages.flatMap((p) => p.items)
  if (items.length === 0) return <p className="text-sm text-muted">No completed reviews or labs yet.</p>
  return (
    <div>
      <ul className="divide-y divide-line border-y border-line">
        {items.map((item) => (
          <li key={`${item.type}-${item.id}`}>
            <HistoryRow item={item} />
          </li>
        ))}
      </ul>
      {history.hasNextPage && (
        <Button type="button" variant="secondary" className="mt-4" busy={history.isFetchingNextPage} onClick={() => history.fetchNextPage()}>
          Show more
        </Button>
      )}
    </div>
  )
}

function HistoryRow({ item: i }: { item: ProgressHistoryItem }) {
  const to = i.type === 'REVIEW' ? `/reviews/${i.id}` : `/repositories/${i.repositoryId}/scenario-labs/${i.id}`
  return (
    <Link to={to} className="grid gap-x-6 gap-y-1 py-3 text-sm transition-colors hover:bg-raised/40 sm:grid-cols-[7rem_minmax(0,1fr)_auto]">
      <span className="text-muted">{formatDate(i.date)}</span>{' '}
      <span className="min-w-0">
        <span className="text-ink">{i.type === 'REVIEW' ? 'Review' : 'Scenario Lab'}</span>{' '}
        <span className="text-muted">
          · {i.repositoryName} · <span className="font-mono text-xs">{shortCommit(i.commitSha)}</span>
        </span>{' '}
        {i.type === 'LAB' && i.roles && i.seniority && (
          <span className="block text-xs text-muted">
            {rolesLabel(i.roles)} · {SENIORITY_LABELS[i.seniority]} · {i.scenariosSubmitted} of {i.scenariosGenerated} submitted
          </span>
        )}
      </span>
      <span className="flex items-center sm:justify-end">
        {i.type === 'REVIEW' && i.overall && <AssessmentMark value={i.overall} />}
      </span>
    </Link>
  )
}
