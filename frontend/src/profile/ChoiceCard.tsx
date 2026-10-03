import type { ReactNode } from 'react'

/** A radio button styled as a selectable card. */
export function ChoiceCard({
  name,
  checked,
  onSelect,
  title,
  description,
}: {
  name: string
  checked: boolean
  onSelect: () => void
  title: ReactNode
  description?: ReactNode
}) {
  return (
    <label
      className={`flex cursor-pointer items-start gap-3 rounded-md border px-3.5 py-3 text-sm transition-colors has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-white ${
        checked ? 'border-ink bg-raised text-ink' : 'border-line text-muted hover:border-line-strong hover:text-ink'
      }`}
    >
      <input type="radio" name={name} checked={checked} onChange={onSelect} className="sr-only" />
      <span
        aria-hidden
        className={`mt-0.5 grid size-4 shrink-0 place-items-center rounded-full border ${checked ? 'border-white' : 'border-line-strong'}`}
      >
        {checked && <span className="size-2 rounded-full bg-white" />}
      </span>
      <span>
        <span className="block">{title}</span>
        {description && <span className="mt-0.5 block text-xs text-muted">{description}</span>}
      </span>
    </label>
  )
}
