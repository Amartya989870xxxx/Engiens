import type { Assessment } from '../api'

/** Words, not scores. The mark count is a quiet visual cue only. */
export const ASSESSMENT: Record<Assessment, { label: string; marks: number }> = {
  STRONG: { label: 'Strong', marks: 4 },
  SOLID: { label: 'Solid', marks: 3 },
  DEVELOPING: { label: 'Developing', marks: 2 },
  NEEDS_ATTENTION: { label: 'Needs attention', marks: 1 },
  NOT_ASSESSABLE: { label: 'Not assessable', marks: 0 },
}

export const titleCase = (value: string) => value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ')
