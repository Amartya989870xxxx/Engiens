import type { ExperienceLevel, Profile, ProfileRequest, WorkExperience } from '../api'
import { GOAL_TEMPLATES, yearsOfStudy } from './options'

/** Everything the onboarding cards collect, before it becomes a ProfileRequest. */
export type Draft = {
  name: string
  level: ExperienceLevel | ''
  classYear: number | null
  workExperience: WorkExperience | ''
  languages: string[]
  frameworks: string[]
  databases: string[]
  experienceAreas: string[]
  githubUrl: string
  /** 'private' means repositories are shared through the connected GitHub App. */
  githubAccess: 'public' | 'private'
  /** Index into GOAL_TEMPLATES, 'other' for a written goal, or null when nothing is picked. */
  goalChoice: number | 'other' | null
  customGoal: string
}

export const STEP_COUNT = 4

export function initialDraft(profile: Profile | null, defaultName: string): Draft {
  const goal = profile?.goals ?? null
  const templateIndex = goal ? GOAL_TEMPLATES.indexOf(goal) : -1
  return {
    name: profile?.name ?? defaultName,
    level: profile?.level ?? '',
    classYear: profile?.classYear ?? null,
    workExperience: profile?.workExperience ?? '',
    languages: profile?.languages ?? [],
    frameworks: profile?.frameworks ?? [],
    databases: profile?.databases ?? [],
    experienceAreas: profile?.experienceAreas ?? [],
    githubUrl: profile?.githubUsername ? `https://github.com/${profile.githubUsername}` : '',
    githubAccess: 'public',
    goalChoice: templateIndex >= 0 ? templateIndex : goal ? 'other' : null,
    customGoal: templateIndex >= 0 ? '' : (goal ?? ''),
  }
}

/** Changing status resets the year of study, since valid years depend on the status. */
export function withLevel(draft: Draft, level: ExperienceLevel | ''): Draft {
  return level === draft.level ? draft : { ...draft, level, classYear: null }
}

/** Returns the first problem that blocks leaving this step, or null. */
export function stepError(step: number, d: Draft, githubConnected = false): string | null {
  switch (step) {
    case 0:
      if (!d.name.trim()) return 'Enter your name.'
      if (!d.level) return 'Choose your current status.'
      if (yearsOfStudy(d.level) > 0 && !d.classYear) return 'Choose your year of study.'
      if (d.level === 'PROFESSIONAL' && !d.workExperience) return 'Choose your years of experience.'
      return null
    case 1:
      return d.languages.length === 0 ? 'Pick at least one language.' : null
    case 2:
      return d.githubAccess === 'private' && !githubConnected
        ? 'Connect GitHub, or choose public repositories only.'
        : null
    case 3:
      if (d.goalChoice === null) return 'Pick a goal, or write your own.'
      if (d.goalChoice === 'other' && !d.customGoal.trim()) return 'Write your goal in a sentence.'
      return null
    default:
      return null
  }
}

/** @param githubLogin the connected GitHub account, which wins over a pasted link when sharing privately */
export function toRequest(d: Draft, githubLogin: string | null = null): ProfileRequest {
  if (!d.level) throw new Error('Level is required before saving')
  return {
    name: d.name.trim(),
    level: d.level,
    classYear: yearsOfStudy(d.level) > 0 ? d.classYear : null,
    workExperience: d.level === 'PROFESSIONAL' && d.workExperience ? d.workExperience : null,
    languages: d.languages,
    frameworks: d.frameworks,
    databases: d.databases,
    experienceAreas: d.experienceAreas,
    githubUrl: d.githubAccess === 'private' && githubLogin ? `https://github.com/${githubLogin}` : d.githubUrl.trim() || null,
    goals: d.goalChoice === 'other' ? d.customGoal.trim() : d.goalChoice === null ? null : GOAL_TEMPLATES[d.goalChoice],
  }
}

const SAVED_KEY = 'lens.onboarding'
const SAVED_TTL_MS = 30 * 60 * 1000

/** Keeps answers while the user is away on GitHub choosing repositories. */
export function saveBeforeLeaving(draft: Draft, step: number) {
  try {
    sessionStorage.setItem(SAVED_KEY, JSON.stringify({ draft, step, savedAt: Date.now() }))
  } catch {
    // Storage can be unavailable (private mode); the user just re-enters answers.
  }
}

/** Reads without removing, so React StrictMode's double render sees the same value. */
export function peekSavedDraft(): { draft: Draft; step: number } | null {
  try {
    const raw = sessionStorage.getItem(SAVED_KEY)
    if (!raw) return null
    const saved = JSON.parse(raw)
    return Date.now() - saved.savedAt < SAVED_TTL_MS ? { draft: saved.draft, step: saved.step } : null
  } catch {
    return null
  }
}

export function clearSavedDraft() {
  try {
    sessionStorage.removeItem(SAVED_KEY)
  } catch {
    // Nothing to clear when storage is unavailable.
  }
}
