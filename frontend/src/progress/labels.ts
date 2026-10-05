import type { ProgressIndicator } from '../api'

/** What each indicator is called on screen. Indicators describe stored evidence; they are not grades. */
export const INDICATOR_LABELS: Record<ProgressIndicator, string> = {
  CONSISTENT_STRENGTH: 'Consistent strength',
  RECURRING_GAP: 'Recurring gap',
  IMPROVING: 'Improving',
  INCONSISTENT: 'Inconsistent',
  MIXED: 'No clear pattern',
  NOT_ENOUGH_HISTORY: 'Not enough history',
  NOT_ASSESSED: 'Not assessed',
}

const dateFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' })

export const formatDate = (iso: string | null) => (iso ? dateFormat.format(new Date(iso)) : '')

export const shortCommit = (sha: string | null) => (sha ? sha.slice(0, 7) : '—')
