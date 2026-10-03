import { useQuery } from '@tanstack/react-query'
import { api, type GitHubConnection, type GitHubProjects, type GitHubRepo, type Profile } from '../api'

const TEN_MINUTES = 10 * 60 * 1000

/**
 * The user's most recently updated repositories, used as one-click review picks.
 * Uses the connected GitHub App when there is one (includes private repos),
 * otherwise the public repos behind the profile's GitHub username.
 */
export function useYourRepos(profile: Profile | undefined, limit = 3) {
  const connection = useQuery({
    queryKey: ['github-connection'],
    queryFn: () => api<GitHubConnection>('/api/github/connection'),
  })
  const connected = connection.data?.connected ?? false
  const username = profile?.githubUsername ?? null

  const repos = useQuery({
    queryKey: ['your-repos', connected ? 'connected' : username],
    enabled: connection.isFetched && (connected || username !== null),
    // Public lookups share GitHub's 60-requests-per-hour limit; don't refetch on every visit.
    staleTime: TEN_MINUTES,
    retry: false,
    queryFn: async () => {
      const list = connected
        ? await api<GitHubRepo[]>('/api/github/connection/repositories')
        : (await api<GitHubProjects>(`/api/github/projects?profileUrl=${encodeURIComponent(`https://github.com/${username}`)}`)).projects
      return [...list].sort((a, b) => (b.pushedAt ?? '').localeCompare(a.pushedAt ?? '')).slice(0, limit)
    },
  })

  return { repos: repos.data, loading: repos.isLoading, hasGitHub: connected || username !== null }
}
