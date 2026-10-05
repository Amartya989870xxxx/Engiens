const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'
const TOKEN_KEY = 'lens.token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

/** Shape of every error response from the backend (see ApiError.java). */
export class ApiError extends Error {
  status: number
  code: string
  fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

/** Fired when the server rejects our saved login, so the app can send the user back to log in. */
export const SESSION_EXPIRED_EVENT = 'lens:session-expired'

export async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const token = tokenStore.get()
  let res: Response
  try {
    res = await fetch(`${API_URL}${path}`, {
      method: options.method ?? 'GET',
      headers: {
        ...(options.body !== undefined && { 'Content-Type': 'application/json' }),
        ...(token && { Authorization: `Bearer ${token}` }),
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Check your connection and try again.')
  }
  if (res.status === 401 && token) {
    tokenStore.clear()
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
  }
  if (!res.ok) {
    const err = await res.json().catch(() => null)
    throw new ApiError(
      res.status,
      err?.code ?? 'UNKNOWN',
      err?.message ?? 'Something went wrong. Please try again.',
      err?.fieldErrors ?? {},
    )
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export type User = { id: string; name: string; email: string; profileCompleted: boolean }
export type AuthResponse = { token: string; user: User }

export type ExperienceLevel = 'SCHOOL_STUDENT' | 'UNDERGRADUATE' | 'GRADUATE' | 'PROFESSIONAL'

export type WorkExperience =
  | 'UNDER_ONE_YEAR'
  | 'ONE_TO_TWO_YEARS'
  | 'THREE_TO_FIVE_YEARS'
  | 'SIX_TO_TEN_YEARS'
  | 'OVER_TEN_YEARS'

export type Profile = {
  name: string
  level: ExperienceLevel
  classYear: number | null
  workExperience: WorkExperience | null
  languages: string[]
  frameworks: string[]
  databases: string[]
  experienceAreas: string[]
  githubUsername: string | null
  goals: string | null
}

export type ProfileRequest = Omit<Profile, 'githubUsername'> & { githubUrl: string | null }

export type GitHubRepo = {
  name: string
  description: string | null
  language: string | null
  stars: number
  privateRepo: boolean
  htmlUrl: string
  pushedAt: string | null
}

export type GitHubProjects = { username: string; projects: GitHubRepo[] }

export type GitHubConnection = { available: boolean; connected: boolean; login: string | null; manageUrl: string | null }

export type RepositoryStatus = 'IMPORTING' | 'READY' | 'FAILED'
export type LabelCount = { label: string; count: number }

/** An imported repository, as our API describes it (never GitHub's raw format). */
export type ImportedRepository = {
  id: string
  owner: string
  name: string
  url: string
  description: string | null
  defaultBranch: string
  commitSha: string | null
  primaryLanguage: string | null
  visibility: 'PUBLIC' | 'PRIVATE'
  stars: number
  forks: number
  fileCount: number
  relevantFileCount: number
  ignoredFileCount: number
  status: RepositoryStatus
  failureReason: string | null
  updatedAt: string
  languages: LabelCount[]
  ignoredReasons: LabelCount[]
}

export type RepositoryListItem = { id: string; owner: string; name: string; status: RepositoryStatus; updatedAt: string }

/** Imports a repository by its GitHub link; returns the existing record if it was already imported. */
export const importRepository = (url: string) =>
  api<ImportedRepository>('/api/repositories/import', { method: 'POST', body: { url } })

export const getRepository = (id: string) => api<ImportedRepository>(`/api/repositories/${encodeURIComponent(id)}`)

export const getRepositories = () => api<RepositoryListItem[]>('/api/repositories')

export type Detection = { name: string; confidence: 'HIGH' | 'MEDIUM' | 'LOW'; evidence: string[] }

/** One "prepare for review" run: profile → deterministic signals → context. No AI involved. */
export type AnalysisRun = {
  id: string
  repositoryId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  commitSha: string | null
  failureReason: string | null
  completedAt: string | null
  durationMs: number | null
  stats: {
    filesFetched: number | null
    bytesFetched: number | null
    signalCount: number | null
    contextFileCount: number | null
    contextBytes: number | null
  }
  profile: {
    languages: { name: string; fileCount: number; percentage: number }[]
    frameworks: Detection[]
    databases: Detection[]
  } | null
}

export const prepareRepository = (repositoryId: string) =>
  api<AnalysisRun>(`/api/repositories/${encodeURIComponent(repositoryId)}/analyses`, { method: 'POST' })

/** The latest preparation run, or null if the repository was never prepared. */
export async function getLatestAnalysis(repositoryId: string) {
  try {
    return await api<AnalysisRun>(`/api/repositories/${encodeURIComponent(repositoryId)}/analyses/latest`)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

// ---- AI engineering review (Phase 4D) ----------------------------------------------------------

export type Assessment = 'STRONG' | 'SOLID' | 'DEVELOPING' | 'NEEDS_ATTENTION' | 'NOT_ASSESSABLE'
export type Level3 = 'HIGH' | 'MEDIUM' | 'LOW'

/** Either a file (optionally with lines) or a deterministic signal. Never both. */
export type ReviewEvidence = { file?: string | null; lineStart?: number | null; lineEnd?: number | null; signalId?: string | null }

export type ScaleImpact = {
  currentScale: string | null
  tenX: string | null
  hundredX: string | null
  largeScale: string | null
  confidence: Level3 | null
} | null

export type Concern = {
  id: string
  title: string
  severity: Level3
  confidence: Level3
  description: string
  whyItMatters: string
  engineeringImpact: string
  scaleImpact: ScaleImpact
  evidence: ReviewEvidence[]
  recommendation: string
  suggestedDirection: string | null
  learningValue: { currentLevel: string; nextLevel: string; advancedLevel: string } | null
}

export type DimensionReview = {
  id: string
  name: string
  applicability: 'APPLICABLE' | 'NOT_APPLICABLE'
  assessment: Assessment
  confidence: Level3
  summary: string
  strengths: { title: string; description: string; evidence: ReviewEvidence[] }[]
  concerns: Concern[]
  tradeoffs: { decision: string; benefit: string; cost: string; assessment: 'STRONG' | 'REASONABLE' | 'CONTEXT_DEPENDENT' | 'QUESTIONABLE' }[]
  personalizedAdvice: string[]
}

export type ReviewDocument = {
  reviewSchemaVersion: number
  reviewMetadata: { provider: string; model: string; fallbackUsed: boolean; commitSha: string; generatedAt: string } | null
  overallAssessment: { level: Assessment; confidence: Level3; summary: string; strongestAreas: string[]; highestPriorityAreas: string[] }
  executiveSummary: {
    whatThisProjectDoes: string
    engineeringSummary: string
    strongestAspect: string
    biggestOpportunity: string
    overallScaleConcern: string
  }
  projectUnderstanding: { projectType: string; architectureSummary: string; detectedStack: string[]; importantComponents: string[] }
  dimensions: DimensionReview[]
  crossCuttingFindings: (Omit<Concern, 'suggestedDirection' | 'learningValue'> & { category: string; exampleApproach: string | null })[]
  featureEngineeringReview: {
    feature: string
    correctness: { assessment: Assessment; summary: string }
    implementationQuality: { assessment: Assessment; summary: string }
    edgeCases: { handled: string[]; missing: string[] }
    failureModes: string[]
    scaleConsiderations: string[]
    recommendations: string[]
    evidence: ReviewEvidence[]
  }[]
  scaleReadiness: {
    summary: string
    trafficGrowth: { assessment: Assessment; concerns: string[] }
    dataGrowth: { assessment: Assessment; concerns: string[] }
    concurrency: { assessment: Assessment; concerns: string[] }
    failureRecovery: { assessment: Assessment; concerns: string[] }
    operationalComplexity: { assessment: Assessment; concerns: string[] }
    mostLikelyBottlenecks: { component: string; reason: string; confidence: Level3 }[]
  }
  priorityActions: { priority: number; title: string; reason: string; expectedBenefit: string; difficulty: Level3; relatedDimensions: string[] }[]
  personalizedLearningPlan: {
    youAlreadyDoWell: string[]
    nextThingsToLearn: { topic: string; why: string; connectionToProject: string; suggestedOrder: number }[]
    advancedTopics: string[]
  }
  positiveHighlights: { title: string; description: string; whyThisIsGood: string; evidence: ReviewEvidence[] }[]
  reviewLimitations: string[]
  personalization: { audience: string; basis: string } | null
}

export type ReviewRun = {
  id: string
  repositoryId: string
  repositoryName: string | null
  analysisRunId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  commitSha: string | null
  provider: string | null
  model: string | null
  fallbackUsed: boolean
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
  durationMs: number | null
  review: ReviewDocument | null
}

export type Excerpt = { file: string; lineStart: number; lineEnd: number; lines: { number: number; text: string }[] }

export const startReview = (repositoryId: string, regenerate = false) =>
  api<ReviewRun>(`/api/repositories/${encodeURIComponent(repositoryId)}/reviews${regenerate ? '?regenerate=true' : ''}`, {
    method: 'POST',
  })

export const getReview = (id: string) => api<ReviewRun>(`/api/reviews/${encodeURIComponent(id)}`)

export const getReviewHistory = (repositoryId: string) =>
  api<ReviewRun[]>(`/api/repositories/${encodeURIComponent(repositoryId)}/reviews`)

export const getRecentReviews = () => api<ReviewRun[]>('/api/reviews')

export const getExcerpt = (id: string, file: string, lineStart: number, lineEnd: number) =>
  api<Excerpt>(
    `/api/reviews/${encodeURIComponent(id)}/excerpt?file=${encodeURIComponent(file)}&lineStart=${lineStart}&lineEnd=${lineEnd}`,
  )

// ---- Scenario Lab ---------------------------------------------------------------------------------

export type ScenarioRole =
  | 'FRONTEND_ENGINEER'
  | 'BACKEND_ENGINEER'
  | 'FULL_STACK_ENGINEER'
  | 'DEVOPS_ENGINEER'
  | 'INFRASTRUCTURE_ENGINEER'
  | 'CLOUD_ENGINEER'
  | 'NETWORK_ENGINEER'
  | 'AI_ENGINEER'
  | 'ML_ENGINEER'
  | 'MLOPS_ENGINEER'
  | 'AI_ML_ENGINEER'
  | 'AI_RESEARCHER'
  | 'SOFTWARE_ARCHITECT'
  | 'BROAD_ENGINEERING'
export type Seniority = 'BEGINNER' | 'SDE1' | 'SDE2' | 'SDE3' | 'SENIOR_ARCHITECT'
export type LabStatus = 'GENERATING' | 'ACTIVE' | 'FINALIZING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type ExecutionCapability = 'CODE' | 'APPROACH_ONLY'
export type WorkMode = 'CODE' | 'APPROACH'
export type ScenarioLanguage = 'PYTHON' | 'JAVA' | 'JAVASCRIPT' | 'TYPESCRIPT'
export type EvaluationStatus = 'PENDING' | 'COMPLETED' | 'FAILED'

export type ScenarioSummary = {
  id: string
  position: number
  title: string
  role: ScenarioRole
  category: string
  difficulty: string
  executionCapability: ExecutionCapability
  language: ScenarioLanguage | null
  submitted: boolean
  evaluationStatus: EvaluationStatus | null
}

export type ScenarioLab = {
  id: string
  repositoryId: string
  repositoryName: string | null
  repositoryUrl: string | null
  reviewId: string | null
  commitSha: string
  roles: ScenarioRole[]
  seniority: Seniority
  scenarioCount: number
  scenariosReady: number
  status: LabStatus
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
  scenarios: ScenarioSummary[]
}

export type CreateLabRequest = {
  repositoryUrl?: string
  repositoryId?: string
  reviewId?: string
  roles: ScenarioRole[]
  seniority: Seniority
  scenarioCount: 5 | 10 | 20
}

export type ScenarioEvidence = { file: string; lineStart: number | null; lineEnd: number | null; explanation: string }
export type ScenarioDocument = {
  scenarioSchemaVersion: number
  title: string
  summary: string
  incident: string
  context: string
  task: string
  expectedBehaviour: string[]
  constraints: string[]
  evidence: ScenarioEvidence[]
}
export type WorkspaceFileView = { path: string; editable: boolean; starterContent: string; content: string }
export type FileContent = { path: string; content: string }

export type ScenarioDetail = {
  id: string
  labId: string
  position: number
  scenarioCount: number
  repositoryName: string | null
  commitSha: string
  title: string
  role: ScenarioRole
  seniority: Seniority
  category: string
  difficulty: string
  executionCapability: ExecutionCapability
  language: ScenarioLanguage | null
  document: ScenarioDocument
  files: WorkspaceFileView[]
  draftMode: WorkMode
  draftApproach: string | null
  draftUpdatedAt: string | null
  submitted: boolean
  evaluationStatus: EvaluationStatus | null
}

export type RunStatus = 'PASSED' | 'FAILED' | 'COMPILE_ERROR' | 'RUNTIME_ERROR' | 'TIMEOUT' | 'LIMIT_EXCEEDED'
export type CheckResult = { name: string; passed: boolean; message: string | null; durationMs: number }
export type RunResult = {
  status: RunStatus
  passed: number
  total: number
  durationMs: number
  checks: CheckResult[]
  message: string | null
  stdout: string
  stderr: string
  outputTruncated: boolean
}

export type AttemptStatus = {
  id: string
  scenarioId: string
  mode: WorkMode
  runResult: RunResult | null
  evaluationStatus: EvaluationStatus
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
}

export type ScenarioEvaluation = {
  verdict: Assessment
  confidence: Level3
  assessment: string
  whatWasCorrect: string[]
  whatWasMissed: string[]
  rootCause: string
  engineeringJudgment: string
  tradeoffs: string[]
  scaleImpact: string
  regressionRisk: string
  testingAssessment: string
  recommendedFix: string
  referenceApproach: string
}

export type LabAssessment = {
  overallAssessment: { summary: string; engineeringLevel: string; confidence: Level3 }
  strengths: string[]
  growthAreas: string[]
  scenarioLearning: { scenarioId: string; learningPoints: string[] }[]
  learningRecommendations: { topic: string; why: string; connectionToProject: string }[]
  limitations: string[]
  personalizedFor: string | null
}

export type ScenarioResult = {
  scenarioId: string
  position: number
  title: string
  role: ScenarioRole
  category: string
  difficulty: string
  executionCapability: ExecutionCapability
  language: ScenarioLanguage | null
  document: ScenarioDocument
  mode: WorkMode
  submittedFiles: FileContent[]
  submittedApproach: string | null
  runResult: RunResult | null
  evaluation: ScenarioEvaluation | null
  reference: {
    expectedConcepts: string[]
    referenceReasoning: string
    solution: { files: FileContent[]; explanation: string } | null
  } | null
  learningPoints: string[]
  submittedAt: string
}

export type LabAssessmentReport = {
  id: string
  number: number
  repositoryId: string
  repositoryName: string | null
  repositoryUrl: string | null
  reviewId: string | null
  commitSha: string
  roles: ScenarioRole[]
  seniority: Seniority
  scenarioCount: number
  createdAt: string
  completedAt: string
  assessment: LabAssessment
  scenarios: ScenarioResult[]
}

export type LabHistoryItem = {
  id: string
  number: number
  roles: ScenarioRole[]
  seniority: Seniority
  scenarioCount: number
  scenariosCompleted: number
  commitSha: string
  reviewId: string | null
  completedAt: string
}

const lab = (labId: string) => `/api/scenario-labs/${encodeURIComponent(labId)}`
const scenario = (labId: string, scenarioId: string) => `${lab(labId)}/scenarios/${encodeURIComponent(scenarioId)}`

export const createLab = (request: CreateLabRequest) => api<ScenarioLab>('/api/scenario-labs', { method: 'POST', body: request })
/** The open lab, or null when there is none (the server answers 204). */
export const getActiveLab = async () => (await api<ScenarioLab | undefined>('/api/scenario-labs/active')) ?? null
export const getLab = (labId: string) => api<ScenarioLab>(lab(labId))
export const cancelLab = (labId: string) => api<ScenarioLab>(`${lab(labId)}/cancel`, { method: 'POST' })
export const retryFinalization = (labId: string) => api<void>(`${lab(labId)}/finalize`, { method: 'POST' })
export const getScenario = (labId: string, scenarioId: string) => api<ScenarioDetail>(scenario(labId, scenarioId))
export const saveDraft = (labId: string, scenarioId: string, draft: { mode: WorkMode; files?: FileContent[]; approach?: string }) =>
  api<void>(`${scenario(labId, scenarioId)}/draft`, { method: 'PUT', body: draft })
export const runScenario = (labId: string, scenarioId: string, files: FileContent[]) =>
  api<RunResult>(`${scenario(labId, scenarioId)}/run`, { method: 'POST', body: { files } })
export const submitScenario = (labId: string, scenarioId: string, body: { mode: WorkMode; files?: FileContent[]; approach?: string }) =>
  api<AttemptStatus>(`${scenario(labId, scenarioId)}/submit`, { method: 'POST', body })
export const retryEvaluation = (labId: string, scenarioId: string) =>
  api<AttemptStatus>(`${scenario(labId, scenarioId)}/attempt/retry-evaluation`, { method: 'POST' })
export const getLabHistory = (repositoryId: string) =>
  api<LabHistoryItem[]>(`/api/repositories/${encodeURIComponent(repositoryId)}/scenario-labs`)
export const getLabAssessment = (labId: string) => api<LabAssessmentReport>(`${lab(labId)}/assessment`)

/**
 * Downloads a file the server generates (e.g. a PDF) with the user's token, then hands it to the browser
 * as a normal download. A plain link can't send the Authorization header.
 */
export async function downloadFile(path: string, fallbackName: string) {
  const token = tokenStore.get()
  let res: Response
  try {
    res = await fetch(`${API_URL}${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Check your connection and try again.')
  }
  if (!res.ok) {
    const err = await res.json().catch(() => null)
    throw new ApiError(res.status, err?.code ?? 'UNKNOWN', err?.message ?? 'The download failed. Please try again.')
  }
  const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
/** One evaluated scenario's feedback, readable while its lab is still open. */
export const getScenarioFeedback = (labId: string, scenarioId: string) => api<ScenarioResult>(`${scenario(labId, scenarioId)}/feedback`)
