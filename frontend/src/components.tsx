import { Logo } from './shell/Logo'
import { Link, Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './useAuth'

export function ProtectedRoute() {
  const { user, loading } = useAuth()
  if (loading) return <p className="p-8 text-muted">Loading…</p>
  if (!user) return <Navigate to="/login" replace />
  return <Outlet />
}

/** Sends users without a profile to onboarding before anything else. */
export function RequireProfile() {
  const { user } = useAuth()
  if (user && !user.profileCompleted) return <Navigate to="/onboarding" replace />
  return <Outlet />
}

/**
 * Onboarding happens once. Anyone who already has a profile is sent to /profile instead.
 * The query string is kept because GitHub's connect flow returns to /onboarding?github=…
 */
export function RequireNoProfile() {
  const { user } = useAuth()
  const location = useLocation()
  if (user?.profileCompleted) return <Navigate to={`/profile${location.search}`} replace />
  return <Outlet />
}

/** Minimal header for the one-time onboarding flow. */
export function Layout() {
  const { user, logout } = useAuth()
  return (
    <div className="min-h-screen bg-canvas">
      <header className="border-b border-line">
        <div className="mx-auto flex max-w-4xl items-center justify-between px-4 py-3.5">
          <Link to="/">
            <Logo />
          </Link>
          {user && (
            <div className="flex items-center gap-5 text-sm">
              <button onClick={logout} className="text-muted transition-colors hover:text-ink">
                Log out
              </button>
            </div>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-4xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
