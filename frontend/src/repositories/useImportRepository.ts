import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { importRepository } from '../api'

/** Imports a repository, then opens its overview page. Used by the dashboard and the retry button. */
export function useImportRepository() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  return useMutation({
    mutationFn: importRepository,
    onSuccess: async (repo) => {
      // The overview page renders straight from this cache entry, with no second request.
      qc.setQueryData(['repository', repo.id], repo)
      navigate(`/repositories/${repo.id}`)
      await qc.invalidateQueries({ queryKey: ['repositories'] })
    },
  })
}
