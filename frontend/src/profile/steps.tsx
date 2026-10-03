import type { ExperienceLevel, WorkExperience } from '../api'
import { Field, inputClass, labelClass } from '../ui'
import { ChoiceCard } from './ChoiceCard'
import { GitHubSection, type PrivateAccess, type PublicLookup } from './GitHubSection'
import type { Draft } from './draft'
import { withLevel } from './draft'
import {
  DATABASES,
  EXPERIENCE_SUGGESTIONS,
  FRAMEWORKS,
  GOAL_TEMPLATES,
  LANGUAGES,
  LEVELS,
  ordinalYear,
  WORK_EXPERIENCE,
  yearsOfStudy,
} from './options'
import { TagSelect } from './TagSelect'

type StepProps = { draft: Draft; update: (next: Draft) => void }

// Placeholder-style grey until a real option is chosen.
const selectClass = (empty: boolean) => `${empty ? inputClass.replace('text-ink', 'text-muted') : inputClass} select-chevron h-10`

export function AboutStep({ draft, update }: StepProps) {
  const years = yearsOfStudy(draft.level)
  return (
    <>
      <Field
        label="Your name"
        value={draft.name}
        onChange={(e) => update({ ...draft, name: e.target.value })}
        maxLength={100}
        autoComplete="name"
        autoFocus
      />
      <label className="block">
        <span className={labelClass}>Current status</span>
        <select
          value={draft.level}
          onChange={(e) => update(withLevel(draft, e.target.value as ExperienceLevel | ''))}
          className={selectClass(!draft.level)}
        >
          <option value="" disabled>
            Select one
          </option>
          {LEVELS.map((l) => (
            <option key={l.value} value={l.value}>
              {l.label}
            </option>
          ))}
        </select>
      </label>
      {years > 0 && (
        // Keyed by level so switching undergrad ↔ graduate replays the fade with the new options.
        <label key={draft.level} className="block animate-fade-in motion-reduce:animate-none">
          <span className={labelClass}>Year of study</span>
          <select
            value={draft.classYear ?? ''}
            onChange={(e) => update({ ...draft, classYear: Number(e.target.value) })}
            className={selectClass(!draft.classYear)}
          >
            <option value="" disabled>
              Select year
            </option>
            {Array.from({ length: years }, (_, i) => i + 1).map((y) => (
              <option key={y} value={y}>
                {ordinalYear(y)}
              </option>
            ))}
          </select>
        </label>
      )}
      {draft.level === 'PROFESSIONAL' && (
        <label className="block animate-fade-in motion-reduce:animate-none">
          <span className={labelClass}>Years of experience</span>
          <select
            value={draft.workExperience}
            onChange={(e) => update({ ...draft, workExperience: e.target.value as WorkExperience })}
            className={selectClass(!draft.workExperience)}
          >
            <option value="" disabled>
              Select range
            </option>
            {WORK_EXPERIENCE.map((w) => (
              <option key={w.value} value={w.value}>
                {w.label}
              </option>
            ))}
          </select>
        </label>
      )}
    </>
  )
}

export function StackStep({ draft, update }: StepProps) {
  return (
    <>
      <TagSelect
        label="Languages"
        options={LANGUAGES}
        value={draft.languages}
        onChange={(languages) => update({ ...draft, languages })}
        placeholder="Search languages"
      />
      <TagSelect
        label="Frameworks and tools"
        options={FRAMEWORKS}
        value={draft.frameworks}
        onChange={(frameworks) => update({ ...draft, frameworks })}
        placeholder="Search frameworks and tools"
      />
      <TagSelect
        label="Databases"
        options={DATABASES}
        value={draft.databases}
        onChange={(databases) => update({ ...draft, databases })}
        placeholder="Search databases"
      />
    </>
  )
}

export function ExperienceStep({ draft, update, lookup, access }: StepProps & { lookup: PublicLookup; access: PrivateAccess }) {
  return (
    <>
      <TagSelect
        label="Where do you feel experienced?"
        options={EXPERIENCE_SUGGESTIONS}
        value={draft.experienceAreas}
        onChange={(experienceAreas) => update({ ...draft, experienceAreas })}
        placeholder="Type anything, e.g. REST APIs, then press Enter"
      />
      <GitHubSection draft={draft} update={update} lookup={lookup} access={access} />
    </>
  )
}

export function GoalsStep({ draft, update }: StepProps) {
  const choices: { key: number | 'other'; label: string }[] = [
    ...GOAL_TEMPLATES.map((label, i) => ({ key: i, label })),
    { key: 'other', label: 'Something else' },
  ]
  return (
    <fieldset className="space-y-2">
      <legend className="sr-only">Your current goal</legend>
      {choices.map((c) => (
        <ChoiceCard
          key={c.key}
          name="goal"
          checked={draft.goalChoice === c.key}
          onSelect={() => update({ ...draft, goalChoice: c.key })}
          title={c.label}
        />
      ))}
      {draft.goalChoice === 'other' && (
        <label className="block pt-2 animate-fade-in motion-reduce:animate-none">
          <span className="sr-only">Describe your goal</span>
          <textarea
            value={draft.customGoal}
            onChange={(e) => update({ ...draft, customGoal: e.target.value })}
            rows={3}
            maxLength={1000}
            autoFocus
            placeholder="e.g. Contribute to an open-source database project"
            className={`${inputClass} resize-none`}
          />
        </label>
      )}
    </fieldset>
  )
}
