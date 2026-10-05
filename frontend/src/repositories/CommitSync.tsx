import { useMutation, useQueryClient } from '@tanstack/react-query'
import { syncRepository, type ImportedRepository } from '../api'
import { Button, ErrorBanner } from '../ui'

const short = (sha: string | null) => (sha ? sha.slice(0, 7) : '—')

/**
 * Moves the repository to the latest commit of its default branch, so the next review reads the new code. Earlier
 * reviews and labs keep the commit they assessed, which is what lets Progress compare before and after.
 */
export function CommitSync({ repo }: { repo: ImportedRepository }) {
  const queryClient = useQueryClient()
  const sync = useMutation({
    mutationFn: () => syncRepository(repo.id),
    onSuccess: (result) => {
      queryClient.setQueryData(['repository', repo.id], result.repository)
      if (result.changed) void queryClient.invalidateQueries()
    },
  })
  return (
    <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
      <Button type="button" variant="secondary" busy={sync.isPending} onClick={() => sync.mutate()}>
        Check for new commits
      </Button>
      {sync.data && (
        <p role="status" className="text-sm text-ink/85">
          {sync.data.changed ? (
            <>
              Moved from <span className="font-mono text-[13px]">{short(sync.data.previousCommit)}</span> to{' '}
              <span className="font-mono text-[13px]">{short(sync.data.repository.commitSha)}</span>. Run a review to assess the new
              code; earlier reviews keep their commit.
            </>
          ) : (
            <>
              Up to date: <span className="font-mono text-[13px]">{short(sync.data.repository.commitSha)}</span> is the latest commit on{' '}
              {sync.data.repository.defaultBranch}.
            </>
          )}
        </p>
      )}
      <ErrorBanner error={sync.error} />
    </div>
  )
}
