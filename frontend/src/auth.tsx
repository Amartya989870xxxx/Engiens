import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, SESSION_EXPIRED_EVENT, tokenStore, type AuthResponse, type User } from './api'

type AuthState = {
  user: User | null
  loading: boolean
  /** True after the server rejected a saved login (usually because it expired). */
  sessionExpired: boolean
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient()
  // The token is mirrored in React state: React only re-renders when state changes,
  // and it can't see writes to localStorage. Every login/logout goes through setToken.
  const [token, setToken] = useState(tokenStore.get)
  const [sessionExpired, setSessionExpired] = useState(false)

  useEffect(() => {
    // api() has already removed the stored token; forget the user and their cached data.
    const onExpired = () => {
      setSessionExpired(true)
      setToken(null)
      qc.clear()
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired)
  }, [qc])

  const me = useQuery({
    queryKey: ['me'],
    queryFn: async () => {
      try {
        return await api<User>('/api/auth/me')
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    enabled: token !== null,
    retry: false,
  })

  const onAuth = (res: AuthResponse) => {
    setSessionExpired(false)
    tokenStore.set(res.token)
    qc.setQueryData(['me'], res.user)
    setToken(res.token)
  }

  const loginMutation = useMutation({
    mutationFn: (v: { email: string; password: string }) =>
      api<AuthResponse>('/api/auth/login', { method: 'POST', body: v }),
    onSuccess: onAuth,
  })
  const registerMutation = useMutation({
    mutationFn: (v: { name: string; email: string; password: string }) =>
      api<AuthResponse>('/api/auth/register', { method: 'POST', body: v }),
    onSuccess: onAuth,
  })

  const value: AuthState = {
    user: token ? (me.data ?? null) : null,
    loading: token !== null && me.isPending,
    sessionExpired,
    login: async (email, password) => void (await loginMutation.mutateAsync({ email, password })),
    register: async (name, email, password) => void (await registerMutation.mutateAsync({ name, email, password })),
    logout: () => {
      tokenStore.clear()
      setToken(null) // triggers the re-render that sends protected pages to /login
      qc.clear() // drop the previous user's cached data so the next user never sees it
    },
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
