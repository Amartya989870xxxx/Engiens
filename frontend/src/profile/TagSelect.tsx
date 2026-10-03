import { useId, useMemo, useState, type KeyboardEvent } from 'react'
import { labelClass } from '../ui'

const MAX_TAGS = 20
const MAX_TAG_LENGTH = 40

type Props = {
  label: string
  options: string[]
  value: string[]
  onChange: (value: string[]) => void
  placeholder?: string
}

/**
 * Searchable multi-select. Picks from `options`, and also accepts anything
 * typed that isn't listed, so nobody is blocked by a gap in our lists.
 */
export function TagSelect({ label, options, value, onChange, placeholder }: Props) {
  const id = useId()
  const listId = `${id}-list`
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  const typed = query.replace(/,/g, ' ').trim().slice(0, MAX_TAG_LENGTH)
  const full = value.length >= MAX_TAGS

  const choices = useMemo(() => {
    const chosen = new Set(value.map((v) => v.toLowerCase()))
    const q = typed.toLowerCase()
    const matches = options.filter((o) => !chosen.has(o.toLowerCase()) && o.toLowerCase().includes(q))
    // Prefix matches first, so typing "ja" puts Java above "Objective-C"-style substring hits.
    matches.sort((a, b) => Number(!a.toLowerCase().startsWith(q)) - Number(!b.toLowerCase().startsWith(q)))
    const exists = options.some((o) => o.toLowerCase() === q) || chosen.has(q)
    return typed && !exists ? [...matches, `Add “${typed}”`] : matches
  }, [options, value, typed])

  function pick(index: number) {
    const choice = choices[index]
    if (choice === undefined || full) return
    const tag = choice.startsWith('Add “') ? typed : choice
    onChange([...value, tag])
    setQuery('')
    setActive(0)
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setActive((a) => Math.min(a + 1, choices.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((a) => Math.max(a - 1, 0))
    } else if (e.key === 'Enter' || (e.key === ',' && typed)) {
      if (open && choices.length > 0) {
        e.preventDefault()
        pick(active)
      }
    } else if (e.key === 'Backspace' && !query && value.length > 0) {
      onChange(value.slice(0, -1))
    } else if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  const showList = open && !full && choices.length > 0
  return (
    <div className="relative">
      <label htmlFor={id} className={labelClass}>
        {label}
      </label>
      <div className="flex min-h-10 flex-wrap items-center gap-1.5 rounded-md border border-line bg-canvas px-2 py-1.5 transition-colors focus-within:border-ink hover:border-line-strong">
        {value.map((tag) => (
          <span key={tag} className="inline-flex items-center gap-1 rounded bg-raised py-0.5 pl-2 pr-1 text-[13px] text-ink">
            {tag}
            <button
              type="button"
              onClick={() => onChange(value.filter((v) => v !== tag))}
              aria-label={`Remove ${tag}`}
              className="rounded px-1 text-muted hover:text-ink focus-visible:outline-1 focus-visible:outline-white"
            >
              ×
            </button>
          </span>
        ))}
        <input
          id={id}
          role="combobox"
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showList ? `${listId}-${active}` : undefined}
          value={query}
          disabled={full}
          onChange={(e) => {
            setQuery(e.target.value)
            setActive(0)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onKeyDown={onKeyDown}
          placeholder={full ? `Up to ${MAX_TAGS}` : value.length ? '' : placeholder}
          className="min-w-24 flex-1 bg-transparent px-1 py-0.5 text-sm text-ink placeholder:text-muted/70 focus:outline-none"
        />
      </div>
      {showList && (
        <ul
          id={listId}
          role="listbox"
          aria-label={label}
          className="absolute z-20 mt-1 max-h-56 w-full overflow-auto rounded-md border border-line bg-surface py-1 shadow-2xl shadow-black/60 animate-fade-in motion-reduce:animate-none"
        >
          {choices.map((choice, i) => (
            <li
              key={choice}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              // mousedown, not click: keeps focus in the input so the list doesn't close first.
              onMouseDown={(e) => {
                e.preventDefault()
                pick(i)
              }}
              onMouseEnter={() => setActive(i)}
              className={`cursor-pointer px-3 py-1.5 text-sm ${i === active ? 'bg-raised text-ink' : 'text-muted'}`}
            >
              {choice}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
