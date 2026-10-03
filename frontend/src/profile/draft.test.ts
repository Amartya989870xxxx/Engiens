import { expect, test } from 'vitest'
import type { Profile } from '../api'
import { initialDraft, stepError, toRequest, withLevel, type Draft } from './draft'
import { GOAL_TEMPLATES } from './options'

const base: Draft = { ...initialDraft(null, 'Asha'), level: 'UNDERGRADUATE', classYear: 2, languages: ['Python'], goalChoice: 0 }

test('university levels need a year; others do not', () => {
  expect(stepError(0, { ...base, classYear: null })).toBe('Choose your year of study.')
  expect(stepError(0, { ...base, level: 'PROFESSIONAL', classYear: null })).toBe('Choose your years of experience.')
  expect(stepError(0, { ...base, level: 'PROFESSIONAL', classYear: null, workExperience: 'ONE_TO_TWO_YEARS' })).toBeNull()
  expect(stepError(0, { ...base, level: '' })).toBe('Choose your current status.')
})

test('changing status clears the year so a 4th-year undergrad cannot become a 4th-year graduate', () => {
  expect(withLevel({ ...base, classYear: 4 }, 'GRADUATE').classYear).toBeNull()
  expect(withLevel(base, 'UNDERGRADUATE').classYear).toBe(2)
})

test('stack step needs a language and goals step needs a goal', () => {
  expect(stepError(1, { ...base, languages: [] })).not.toBeNull()
  expect(stepError(3, { ...base, goalChoice: null })).not.toBeNull()
  expect(stepError(3, { ...base, goalChoice: 'other', customGoal: '  ' })).not.toBeNull()
})

test('builds the request from a template or a written goal', () => {
  expect(toRequest(base).goals).toBe(GOAL_TEMPLATES[0])
  expect(toRequest({ ...base, goalChoice: 'other', customGoal: ' Ship an OSS lib ' }).goals).toBe('Ship an OSS lib')
  expect(toRequest({ ...base, level: 'SCHOOL_STUDENT' }).classYear).toBeNull()
  expect(toRequest({ ...base, workExperience: 'OVER_TEN_YEARS' }).workExperience).toBeNull()
  expect(toRequest({ ...base, githubUrl: '  ' }).githubUrl).toBeNull()
})

test('restores a saved profile, including a written goal', () => {
  const saved: Profile = {
    name: 'Asha', level: 'GRADUATE', classYear: 1, workExperience: null, languages: ['Go'], frameworks: [], databases: [],
    experienceAreas: [], githubUsername: 'asha', goals: 'My own goal',
  }
  const d = initialDraft(saved, '')
  expect(d.githubUrl).toBe('https://github.com/asha')
  expect(d.goalChoice).toBe('other')
  expect(d.customGoal).toBe('My own goal')
})

test('sharing private repositories requires a connected GitHub account', () => {
  const sharing: Draft = { ...base, githubAccess: 'private' }
  expect(stepError(2, sharing, false)).toBe('Connect GitHub, or choose public repositories only.')
  expect(stepError(2, sharing, true)).toBeNull()
  expect(toRequest({ ...sharing, githubUrl: 'https://github.com/someone-else' }, 'asha').githubUrl).toBe('https://github.com/asha')
})
