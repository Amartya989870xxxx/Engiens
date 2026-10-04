import type { Assessment } from '../api'
import { ASSESSMENT } from './assessment'

/** Four small marks plus the word. "Not assessable" shows a dash: absence of evidence isn't a low grade. */
export function AssessmentMark({ value, showLabel = true }: { value: Assessment; showLabel?: boolean }) {
  const { label, marks } = ASSESSMENT[value]
  return (
    <span className="inline-flex items-center gap-2.5 whitespace-nowrap">
      <span aria-hidden className="flex gap-[3px]">
        {value === 'NOT_ASSESSABLE' ? (
          <span className="h-px w-[25px] bg-line-strong" />
        ) : (
          [1, 2, 3, 4].map((i) => (
            <span key={i} className={`h-2.5 w-[4px] rounded-[1px] ${i <= marks ? 'bg-ink' : 'bg-line-strong'}`} />
          ))
        )}
      </span>
      {showLabel && <span className={`text-sm ${value === 'NOT_ASSESSABLE' ? 'text-muted' : 'text-ink'}`}>{label}</span>}
    </span>
  )
}
