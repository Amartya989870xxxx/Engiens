import { BRAND } from '../brand'
import { Logo } from '../shell/Logo'
import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { Button, Card, ErrorBanner, Field, fieldError } from '../ui'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { user, login, register, sessionExpired } = useAuth()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  if (user) return <Navigate to="/dashboard" replace />

  const isLogin = mode === 'login'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (isLogin) await login(email, password)
      else await register(name, email, password)
      navigate('/dashboard')
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto mt-16 max-w-sm px-4">
      <h1 className="mb-8 flex justify-center">
        <Link to="/" aria-label={`${BRAND} home`}>
          <Logo size={32} />
        </Link>
      </h1>
      <Card title={isLogin ? 'Log in' : 'Create your account'}>
        {sessionExpired && isLogin && (
          <p role="status" className="mb-4 rounded-md border border-line bg-raised px-3 py-2 text-sm text-ink">
            Your session expired. Log in again to continue.
          </p>
        )}
        <form onSubmit={submit} className="space-y-4">
          {!isLogin && (
            <Field label="Name" value={name} onChange={(e) => setName(e.target.value)} error={fieldError(error, 'name')} required />
          )}
          <Field label="Email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} error={fieldError(error, 'email')} required />
          <Field
            label="Password"
            type="password"
            autoComplete={isLogin ? 'current-password' : 'new-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={fieldError(error, 'password')}
            required
          />
          <ErrorBanner error={error} />
          <Button type="submit" busy={busy} className="w-full">
            {isLogin ? 'Log in' : 'Sign up'}
          </Button>
        </form>
      </Card>
      <p className="mt-4 text-center text-sm text-muted">
        {isLogin ? (
          <>
            New here? <Link className="text-ink underline underline-offset-4" to="/register">Create an account</Link>
          </>
        ) : (
          <>
            Already registered? <Link className="text-ink underline underline-offset-4" to="/login">Log in</Link>
          </>
        )}
      </p>
    </div>
  )
}
