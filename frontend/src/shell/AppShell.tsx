import { Logo } from './Logo'
import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, getRecentReviews, getRepositories, type Profile } from '../api'
import { useAuth } from '../useAuth'
import { describeLevel } from '../profile/options'
import { ChartIcon, CloseIcon, ComposeIcon, FlaskIcon, LogOutIcon, MenuIcon, UserIcon } from './icons'

/** Signed-in layout: navigation sidebar on the left, the current page on the right. */
export function AppShell() {
  const location = useLocation()
  // On phones the sidebar is a drawer. It stays open only on the page where it was opened: navigating closes it.
  const [openOn, setOpenOn] = useState<string | null>(null)
  const menuOpen = openOn === location.key
  const setMenuOpen = (open: boolean) => setOpenOn(open ? location.key : null)

  return (
    <div className="min-h-screen bg-canvas">
      <div className="sticky top-0 z-30 flex items-center gap-3 border-b border-line bg-canvas px-4 py-3 md:hidden">
        <button
          type="button"
          onClick={() => setMenuOpen(true)}
          aria-label="Open menu"
          className="rounded-md p-1 text-muted hover:text-ink focus-visible:outline-2 focus-visible:outline-white"
        >
          <MenuIcon />
        </button>
        <Link to="/">
          <Logo size={22} />
        </Link>
      </div>

      {menuOpen && <div className="fixed inset-0 z-40 bg-black/60 md:hidden" onClick={() => setMenuOpen(false)} aria-hidden />}

      <aside
        className={`fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r border-line bg-[#070707] transition-transform duration-200 motion-reduce:transition-none md:translate-x-0 ${
          menuOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <Sidebar onClose={() => setMenuOpen(false)} />
      </aside>

      <main className="md:pl-64">
        <Outlet />
      </main>
    </div>
  )
}

function Sidebar({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  return (
    <>
      <div className="flex items-center justify-between px-5 pb-4 pt-5">
        <Link to="/">
          <Logo />
        </Link>
        <button type="button" onClick={onClose} aria-label="Close menu" className="text-muted hover:text-ink md:hidden">
          <CloseIcon />
        </button>
      </div>

      <nav className="space-y-0.5 px-3" aria-label="Main">
        {/* A fresh navigation (new location.key) tells the dashboard to focus its input, even if we're already there. */}
        <button
          type="button"
          onClick={() => navigate('/dashboard', { state: { focusInput: true } })}
          className="flex w-full items-center gap-3 rounded-lg bg-raised px-3 py-2 text-sm text-ink transition-colors hover:bg-line focus-visible:outline-2 focus-visible:outline-white"
        >
          <ComposeIcon />
          New review
        </button>
        <NavLink
          to="/scenario-lab"
          className={({ isActive }) =>
            `flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors hover:bg-raised hover:text-ink focus-visible:outline-2 focus-visible:outline-white ${
              isActive ? 'bg-raised text-ink' : 'text-muted'
            }`
          }
        >
          <FlaskIcon />
          Scenario Lab
        </NavLink>
        <NavLink
          to="/progress"
          className={({ isActive }) =>
            `flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors hover:bg-raised hover:text-ink focus-visible:outline-2 focus-visible:outline-white ${
              isActive ? 'bg-raised text-ink' : 'text-muted'
            }`
          }
        >
          <ChartIcon />
          Progress
        </NavLink>
      </nav>

      <RecentRepositories />

      <RecentReviews />

      <div className="mt-auto border-t border-line p-3">
        <AccountMenu />
      </div>
    </>
  )
}

const RECENT_LIMIT = 5

/**
 * Imported repositories, newest first. Kept separate from "Recent reviews": an imported
 * repository hasn't been reviewed yet, and the sidebar shouldn't say it has.
 */
function RecentRepositories() {
  const repos = useQuery({ queryKey: ['repositories'], queryFn: getRepositories })
  if (!repos.data?.length) return null
  return (
    <section className="mt-8 px-3" aria-labelledby="recent-repositories">
      <h2 id="recent-repositories" className="mb-1 px-2 text-xs font-medium text-muted">
        Recent repositories
      </h2>
      <ul className="space-y-0.5">
        {repos.data.slice(0, RECENT_LIMIT).map((r) => (
          <li key={r.id}>
            <NavLink
              to={`/repositories/${r.id}`}
              className={({ isActive }) =>
                `flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-[13px] transition-colors hover:bg-raised hover:text-ink ${
                  isActive ? 'bg-raised text-ink' : 'text-muted'
                }`
              }
            >
              <span className="truncate">{r.name}</span>
              {r.status === 'FAILED' && <span className="shrink-0 text-[11px] text-danger">Failed</span>}
            </NavLink>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Completed engineering reviews, newest first. */
function RecentReviews() {
  const reviews = useQuery({ queryKey: ['recent-reviews'], queryFn: getRecentReviews })
  const items = Array.isArray(reviews.data) ? reviews.data.slice(0, RECENT_LIMIT) : []
  return (
    <section className="mt-8 px-3" aria-labelledby="recent-reviews">
      <h2 id="recent-reviews" className="mb-1 px-2 text-xs font-medium text-muted">
        Recent reviews
      </h2>
      {items.length === 0 ? (
        <p className="px-2 text-[13px] leading-relaxed text-muted/70">Reviews you run will be listed here.</p>
      ) : (
        <ul className="space-y-0.5">
          {items.map((r) => (
            <li key={r.id}>
              <NavLink
                to={`/reviews/${r.id}`}
                className={({ isActive }) =>
                  `block truncate rounded-md px-2 py-1.5 text-[13px] transition-colors hover:bg-raised hover:text-ink ${
                    isActive ? 'bg-raised text-ink' : 'text-muted'
                  }`
                }
              >
                {r.repositoryName ?? 'Review'}
              </NavLink>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** A navigation entry for a feature that isn't built yet: visible, but clearly not clickable. */

function AccountMenu() {
  const { user, logout } = useAuth()
  const profile = useQuery({ queryKey: ['profile'], queryFn: () => api<Profile>('/api/profile') })
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  // Close when clicking anywhere else or pressing Escape.
  useEffect(() => {
    if (!open) return
    const onClick = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false)
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const name = profile.data?.name ?? user?.name ?? ''
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('')
  const p = profile.data

  return (
    <div ref={ref} className="relative">
      {open && (
        <div role="menu" className="absolute inset-x-0 bottom-full mb-2 rounded-lg border border-line bg-surface p-1 shadow-2xl shadow-black/60 animate-fade-in motion-reduce:animate-none">
          <NavLink
            to="/profile"
            role="menuitem"
            className="flex items-center gap-3 rounded-md px-3 py-2 text-sm text-ink hover:bg-raised"
          >
            <UserIcon />
            Profile
          </NavLink>
          <button
            type="button"
            role="menuitem"
            onClick={logout}
            className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm text-ink hover:bg-raised"
          >
            <LogOutIcon />
            Log out
          </button>
        </div>
      )}
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="menu"
        className="flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left transition-colors hover:bg-raised focus-visible:outline-2 focus-visible:outline-white"
      >
        <span className="grid size-8 shrink-0 place-items-center rounded-full bg-white text-xs font-medium text-black">{initials}</span>
        <span className="min-w-0">
          <span className="block truncate text-sm text-ink">{name}</span>
          {p && (
            <span className="block truncate text-xs text-muted">{describeLevel(p.level, p.classYear, p.workExperience)}</span>
          )}
        </span>
      </button>
    </div>
  )
}
