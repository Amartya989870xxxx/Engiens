import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, SESSION_EXPIRED_EVENT, type GitHubProjects, type Profile, type ProfileRequest, type User } from '../api'
import { clearSavedDraft, peekSavedDraft, saveBeforeLeaving, type Draft } from './draft'
import type { PublicLookup } from './GitHubSection'

/**
 * Answers saved before the page was left (to connect GitHub, or because the login expired),
 * plus GitHub's ?github=… result when returning from the connect flow. Read once, then cleared.
 */
export function useRestoredDraft() {
  const [params, setParams] = useSearchParams()
  const [githubOutcome] = useState(() => params.get('github'))
  const [saved] = useState(() => peekSavedDraft())

  useEffect(() => {
    clearSavedDraft()
    if (params.has('github')) setParams({}, { replace: true })
  }, [params, setParams])

  return { githubOutcome, saved }
}

/** If the login expires while editing, keep the unsaved answers so they come back after logging in. */
export function useKeepDraftOnExpiry(draft: Draft, step: number | null) {
  useEffect(() => {
    if (step === null) return
    const keep = () => saveBeforeLeaving(draft, step)
    window.addEventListener(SESSION_EXPIRED_EVENT, keep)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, keep)
  }, [draft, step])
}

/** Looks up the public projects behind a pasted GitHub profile link. */
export function useGitHubLookup(url: string) {
  const lookup = useMutation({
    mutationFn: (u: string) => api<GitHubProjects>(`/api/github/projects?profileUrl=${encodeURIComponent(u)}`),
  })

  /** A pasted link must resolve to a real GitHub profile before saving, so a typo isn't saved silently. */
  async function verify(draft: Draft): Promise<boolean> {
    const link = draft.githubUrl.trim()
    if (draft.githubAccess !== 'public' || !link) return true
    if (lookup.isSuccess && lookup.variables === link) return true
    try {
      await lookup.mutateAsync(link)
      return true
    } catch {
      return false // the error is shown under the field
    }
  }

  const props: PublicLookup = {
    data: lookup.data,
    error: lookup.error,
    isPending: lookup.isPending,
    find: () => lookup.mutate(url),
  }
  return { lookup: props, verify, checking: lookup.isPending }
}

/** Saves the whole profile and updates the cached copies the rest of the app reads. */
export function useSaveProfile(onSaved: () => void) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: ProfileRequest) => api<Profile>('/api/profile', { method: 'PUT', body }),
    onSuccess: (saved) => {
      // Navigate first, then update the cache in the same tick, so route guards see the new
      // "profile completed" state and the new page together (no flash of a redirect).
      onSaved()
      qc.setQueryData(['profile'], saved)
      qc.setQueryData<User | null>(['me'], (me) => (me ? { ...me, name: saved.name, profileCompleted: true } : me))
    },
  })
}
