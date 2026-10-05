import { createContext, useContext } from 'react'
import type { User } from './api'

export type AuthState = {
  user: User | null
  loading: boolean
  /** True after the server rejected a saved login (usually because it expired). */
  sessionExpired: boolean
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
