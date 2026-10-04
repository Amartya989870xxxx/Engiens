/** The lab the user last worked on (this tab), so Scenario Lab can show how it ended exactly once. */
const LAST_LAB = 'lens.lab.last'

export function rememberLab(id: string | null) {
  try {
    if (id) sessionStorage.setItem(LAST_LAB, id)
    else sessionStorage.removeItem(LAST_LAB)
  } catch {
    // storage unavailable: the page simply won't show the ending once
  }
}

export function lastLab() {
  try {
    return sessionStorage.getItem(LAST_LAB)
  } catch {
    return null
  }
}
