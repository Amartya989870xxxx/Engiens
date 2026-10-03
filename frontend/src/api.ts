const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'
const TOKEN_KEY = 'lens.token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

/** Shape of every error response from the backend (see ApiError.java). */
export class ApiError extends Error {
  status: number
  code: string
  fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

/** Fired when the server rejects our saved login, so the app can send the user back to log in. */
export const SESSION_EXPIRED_EVENT = 'lens:session-expired'

export async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const token = tokenStore.get()
  let res: Response
  try {
    res = await fetch(`${API_URL}${path}`, {
      method: options.method ?? 'GET',
      headers: {
        ...(options.body !== undefined && { 'Content-Type': 'application/json' }),
        ...(token && { Authorization: `Bearer ${token}` }),
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Check your connection and try again.')
  }
  if (res.status === 401 && token) {
    tokenStore.clear()
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
  }
  if (!res.ok) {
    const err = await res.json().catch(() => null)
    throw new ApiError(
      res.status,
      err?.code ?? 'UNKNOWN',
      err?.message ?? 'Something went wrong. Please try again.',
      err?.fieldErrors ?? {},
    )
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export type User = { id: string; name: string; email: string; profileCompleted: boolean }
export type AuthResponse = { token: string; user: User }

export type ExperienceLevel = 'SCHOOL_STUDENT' | 'UNDERGRADUATE' | 'GRADUATE' | 'PROFESSIONAL'

export type WorkExperience =
  | 'UNDER_ONE_YEAR'
  | 'ONE_TO_TWO_YEARS'
  | 'THREE_TO_FIVE_YEARS'
  | 'SIX_TO_TEN_YEARS'
  | 'OVER_TEN_YEARS'

export type Profile = {
  name: string
  level: ExperienceLevel
  classYear: number | null
  workExperience: WorkExperience | null
  languages: string[]
  frameworks: string[]
  databases: string[]
  experienceAreas: string[]
  githubUsername: string | null
  goals: string | null
}

export type ProfileRequest = Omit<Profile, 'githubUsername'> & { githubUrl: string | null }

export type GitHubRepo = {
  name: string
  description: string | null
  language: string | null
  stars: number
  privateRepo: boolean
  htmlUrl: string
  pushedAt: string | null
}

export type GitHubProjects = { username: string; projects: GitHubRepo[] }

export type GitHubConnection = { available: boolean; connected: boolean; login: string | null; manageUrl: string | null }

export type RepositoryStatus = 'IMPORTING' | 'READY' | 'FAILED'
export type LabelCount = { label: string; count: number }

/** An imported repository, as our API describes it (never GitHub's raw format). */
export type ImportedRepository = {
  id: string
  owner: string
  name: string
  url: string
  description: string | null
  defaultBranch: string
  primaryLanguage: string | null
  visibility: 'PUBLIC' | 'PRIVATE'
  stars: number
  forks: number
  fileCount: number
  relevantFileCount: number
  ignoredFileCount: number
  status: RepositoryStatus
  failureReason: string | null
  updatedAt: string
  languages: LabelCount[]
  ignoredReasons: LabelCount[]
}

export type RepositoryListItem = { id: string; owner: string; name: string; status: RepositoryStatus; updatedAt: string }

/** Imports a repository by its GitHub link; returns the existing record if it was already imported. */
export const importRepository = (url: string) =>
  api<ImportedRepository>('/api/repositories/import', { method: 'POST', body: { url } })

export const getRepository = (id: string) => api<ImportedRepository>(`/api/repositories/${encodeURIComponent(id)}`)

export const getRepositories = () => api<RepositoryListItem[]>('/api/repositories')
