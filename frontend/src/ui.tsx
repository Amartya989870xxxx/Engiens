import type { ReactNode, InputHTMLAttributes, ButtonHTMLAttributes } from 'react'
import { ApiError } from './api'

export const inputClass =
  'w-full rounded-md border border-line bg-canvas px-3 py-2 text-sm text-ink placeholder:text-muted/70 transition-colors hover:border-line-strong focus:border-ink focus:outline-none'

export const labelClass = 'mb-1.5 block text-[13px] font-medium text-muted'

export function Field({
  label,
  error,
  hint,
  ...props
}: { label: string; error?: string; hint?: string } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="block">
      <span className={labelClass}>{label}</span>
      <input {...props} aria-invalid={error ? true : undefined} className={inputClass} />
      {hint && !error && <span className="mt-1.5 block text-xs text-muted">{hint}</span>}
      {error && <span className="mt-1.5 block text-xs text-danger">{error}</span>}
    </label>
  )
}

const buttonStyles = {
  primary: 'bg-white text-black hover:bg-white/85',
  secondary: 'border border-line bg-transparent text-ink hover:border-line-strong hover:bg-raised',
  ghost: 'bg-transparent text-muted hover:text-ink',
}

export function Button({
  children,
  busy,
  variant = 'primary',
  className = '',
  ...props
}: { busy?: boolean; variant?: keyof typeof buttonStyles } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...props}
      disabled={busy || props.disabled}
      className={`inline-flex h-9 shrink-0 items-center justify-center whitespace-nowrap rounded-md px-4 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white disabled:cursor-not-allowed disabled:opacity-50 ${buttonStyles[variant]} ${className}`}
    >
      {busy ? 'Please wait…' : children}
    </button>
  )
}

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof ApiError ? error.message : 'Something went wrong. Please try again.'
  return (
    <p role="alert" className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
      {message}
    </p>
  )
}

export function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-line bg-surface p-6">
      {title && <h2 className="mb-4 font-display text-2xl text-ink">{title}</h2>}
      {children}
    </section>
  )
}

export function fieldError(error: unknown, field: string) {
  return error instanceof ApiError ? error.fieldErrors[field] : undefined
}
