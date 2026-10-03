import { BRAND } from '../brand'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, type GitHubConnection, type GitHubRepo } from '../api'
import type { PrivateAccess } from './GitHubSection'

/** What to tell the user when GitHub sends them back without a connection. */
const RETURN_ERRORS: Record<string, string> = {
  GITHUB_STATE_INVALID: 'That GitHub link expired. Connect again to continue.',
  GITHUB_AUTH_FAILED: 'GitHub sign-in didn’t finish. Connect again to continue.',
  GITHUB_INSTALLATION_NOT_YOURS: 'That GitHub installation isn’t on your account. Connect again with your own account.',
  GITHUB_NOT_INSTALLED: `${BRAND} wasn’t installed on GitHub. Connect again and choose your repositories.`,
}

export function returnErrorMessage(outcome: string | null) {
  if (!outcome || outcome === 'connected') return null
  return RETURN_ERRORS[outcome] ?? 'Couldn’t connect GitHub. Please try again.'
}

/**
 * Private-repository access through the GitHub App.
 * @param beforeLeave runs just before the browser navigates to GitHub
 */
export function useGitHubAccess(beforeLeave: () => void, returnError: string | null): PrivateAccess {
  const qc = useQueryClient()
  const connection = useQuery({
    queryKey: ['github-connection'],
    queryFn: () => api<GitHubConnection>('/api/github/connection'),
  })
  const connected = connection.data?.connected ?? false

  const repositories = useQuery({
    queryKey: ['github-repositories'],
    enabled: connected,
    retry: false,
    queryFn: async () => {
      try {
        return await api<GitHubRepo[]>('/api/github/connection/repositories')
      } catch (e) {
        // Uninstalled on GitHub's side: the server dropped the link, so refresh our view of it.
        if (e instanceof ApiError && e.code === 'GITHUB_NOT_CONNECTED') qc.invalidateQueries({ queryKey: ['github-connection'] })
        throw e
      }
    },
  })

  const connect = useMutation({
    mutationFn: () => api<{ url: string }>('/api/github/connection', { method: 'POST' }),
    onSuccess: ({ url }) => {
      beforeLeave()
      window.location.assign(url)
    },
  })

  const disconnect = useMutation({
    mutationFn: () => api<void>('/api/github/connection', { method: 'DELETE' }),
    onSuccess: async () => {
      qc.removeQueries({ queryKey: ['github-repositories'] })
      await qc.invalidateQueries({ queryKey: ['github-connection'] })
    },
  })

  return {
    connection: connection.data,
    repositories: repositories.data,
    repositoriesError: repositories.error,
    repositoriesLoading: repositories.isLoading,
    returnError,
    connect: () => connect.mutate(),
    // Stays busy until the browser has actually left for GitHub.
    connecting: connect.isPending || connect.isSuccess,
    connectError: connect.error,
    disconnect: () => disconnect.mutate(),
    disconnecting: disconnect.isPending,
  }
}
