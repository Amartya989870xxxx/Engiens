import type { ScenarioRole, Seniority } from '../api'

export const ROLE_LABELS: Record<ScenarioRole, string> = {
  FRONTEND_ENGINEER: 'Frontend Engineer',
  BACKEND_ENGINEER: 'Backend Engineer',
  FULL_STACK_ENGINEER: 'Full-Stack Engineer',
  DEVOPS_ENGINEER: 'DevOps Engineer',
  INFRASTRUCTURE_ENGINEER: 'Infrastructure Engineer',
  CLOUD_ENGINEER: 'Cloud Engineer',
  NETWORK_ENGINEER: 'Network Engineer',
  AI_ENGINEER: 'AI Engineer',
  ML_ENGINEER: 'ML Engineer',
  MLOPS_ENGINEER: 'MLOps Engineer',
  AI_ML_ENGINEER: 'AI/ML Engineer',
  AI_RESEARCHER: 'AI Researcher',
  SOFTWARE_ARCHITECT: 'Software Architect',
  BROAD_ENGINEERING: 'All / Broad Engineering',
}

export const ROLE_BY_LABEL = Object.fromEntries(Object.entries(ROLE_LABELS).map(([k, v]) => [v, k])) as Record<string, ScenarioRole>

export const SENIORITY: { value: Seniority; label: string; description: string }[] = [
  { value: 'BEGINNER', label: 'Beginner', description: 'Fundamental correctness and straightforward fixes' },
  { value: 'SDE1', label: 'SDE1', description: 'Practical production work: APIs, data, tests, errors' },
  { value: 'SDE2', label: 'SDE2', description: 'Deeper design: scale, concurrency, reliability' },
  { value: 'SDE3', label: 'SDE3', description: 'System-level trade-offs, failure domains, migrations' },
  { value: 'SENIOR_ARCHITECT', label: 'Senior Architect', description: 'Boundaries, long-term evolution, resilience' },
]

export const SENIORITY_LABELS = Object.fromEntries(SENIORITY.map((s) => [s.value, s.label])) as Record<Seniority, string>

export const COUNTS = [5, 10, 20] as const

export const rolesLabel = (roles: ScenarioRole[]) => roles.map((r) => ROLE_LABELS[r]).join(', ')

/** CONCURRENCY_CONSISTENCY → "Concurrency consistency". */
export const humanize = (value: string) => value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ')

export const LANGUAGE_LABELS = { PYTHON: 'Python', JAVA: 'Java', JAVASCRIPT: 'JavaScript', TYPESCRIPT: 'TypeScript' } as const
