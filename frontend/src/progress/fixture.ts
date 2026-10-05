import type { Progress, ProgressArea, ProgressEvidence } from '../api'

const AREA_NAMES: [string, string][] = [
  ['CORRECTNESS_AND_FEATURE_IMPLEMENTATION', 'Correctness & Feature Implementation'],
  ['ARCHITECTURE_AND_MODULARITY', 'Architecture & Modularity'],
  ['SYSTEM_DESIGN_AND_SCALABILITY', 'System Design & Scalability'],
  ['BACKEND_ENGINEERING', 'Backend Engineering'],
  ['API_DESIGN_AND_INTEGRATION', 'API Design & Integration'],
  ['DATA_AND_PERSISTENCE', 'Data & Persistence'],
  ['CONCURRENCY_AND_CONSISTENCY', 'Concurrency & Consistency'],
  ['PERFORMANCE_AND_EFFICIENCY', 'Performance & Efficiency'],
  ['ERROR_HANDLING_AND_RESILIENCE', 'Error Handling & Resilience'],
  ['SECURITY', 'Security'],
  ['TESTING_AND_QUALITY_ASSURANCE', 'Testing & Quality Assurance'],
  ['CODE_QUALITY_AND_MAINTAINABILITY', 'Code Quality & Maintainability'],
  ['PRODUCTION_READINESS_AND_OPERATIONS', 'Production Readiness & Operations'],
  ['DEPENDENCIES_AND_EXTERNAL_SERVICES', 'Dependencies & External Services'],
  ['DOCUMENTATION_AND_DEVELOPER_EXPERIENCE', 'Documentation & Developer Experience'],
  ['FRONTEND_CLIENT_ENGINEERING', 'Frontend & Client Engineering'],
]

export function evidence(overrides: Partial<ProgressEvidence> = {}): ProgressEvidence {
  return {
    source: 'REVIEW',
    level: 'SOLID',
    confidence: 'MEDIUM',
    date: '2026-10-01T10:00:00Z',
    repositoryId: 'repo-1',
    repositoryName: 'orders-api',
    commitSha: 'abc1234def5678',
    reviewId: 'rev-1',
    labId: null,
    attemptId: null,
    category: null,
    scenarioTitle: null,
    counted: true,
    note: null,
    ...overrides,
  }
}

function area(id: string, overrides: Partial<ProgressArea> = {}): ProgressArea {
  return {
    area: id,
    name: AREA_NAMES.find(([a]) => a === id)![1],
    indicator: 'NOT_ASSESSED',
    reason: 'Not assessed yet.',
    projectChanges: [],
    variedOnSameCode: [],
    evidence: [],
    ...overrides,
  }
}

/** A user with no reviews or labs. */
export function emptyProgress(): Progress {
  return {
    scope: { repositoryId: null, repositoryName: null },
    repositories: [],
    evidence: { reviews: 0, repositories: 0, labs: 0, evaluatedAnswers: 0, from: null, to: null, skipped: 0, truncated: false },
    areas: AREA_NAMES.map(([id]) => area(id)),
    practice: { categories: [], roles: [], seniorities: [], weakButUnpractised: [] },
    nextAreas: [],
    recommendations: { fromReview: null, fromLab: null },
  }
}

/** One review, no labs: everything is "not enough history". */
export function oneReviewProgress(): Progress {
  const p = emptyProgress()
  p.repositories = [{ id: 'repo-1', name: 'orders-api' }]
  p.evidence = { ...p.evidence, reviews: 1, repositories: 1, from: '2026-10-01T10:00:00Z', to: '2026-10-01T10:00:00Z' }
  p.areas = AREA_NAMES.map(([id]) =>
    area(id, {
      indicator: 'NOT_ENOUGH_HISTORY',
      reason: 'Assessed on one occasion (1 review). Not enough history to identify a trend.',
      evidence: [evidence({ level: id === 'TESTING_AND_QUALITY_ASSURANCE' ? 'DEVELOPING' : 'SOLID' })],
    }),
  )
  p.practice.weakButUnpractised = ['TESTING_AND_QUALITY_ASSURANCE']
  p.nextAreas = [{ area: 'TESTING_AND_QUALITY_ASSURANCE', name: 'Testing & Quality Assurance', why: 'Rated Developing in your latest review of orders-api, and not practised in Scenario Lab yet.' }]
  p.recommendations.fromReview = {
    reviewId: 'rev-1',
    labId: null,
    repositoryId: 'repo-1',
    repositoryName: 'orders-api',
    date: '2026-10-01T10:00:00Z',
    topics: [{ topic: 'Integration tests for the order flow', why: 'The checkout path has no test.' }],
  }
  return p
}

/** Two repositories, reviews at two commits, and a completed lab. */
export function richProgress(): Progress {
  const p = oneReviewProgress()
  p.repositories = [
    { id: 'repo-1', name: 'orders-api' },
    { id: 'repo-2', name: 'payments' },
  ]
  p.evidence = { reviews: 3, repositories: 2, labs: 1, evaluatedAnswers: 2, from: '2026-09-01T10:00:00Z', to: '2026-10-01T10:00:00Z', skipped: 0, truncated: false }
  const lab = evidence({
    source: 'SCENARIO',
    level: 'STRONG',
    reviewId: null,
    labId: 'lab-1',
    attemptId: 'att-1',
    category: 'CONCURRENCY_CONSISTENCY',
    scenarioTitle: 'Prevent duplicate orders under retries',
    date: '2026-10-02T10:00:00Z',
  })
  p.areas = p.areas.map((a) => {
    if (a.area === 'CONCURRENCY_AND_CONSISTENCY') {
      return {
        ...a,
        indicator: 'CONSISTENT_STRENGTH',
        reason: 'Solid or Strong in 2 of 2 assessments (1 review, 1 Scenario Lab answer), including the latest.',
        evidence: [lab, evidence()],
      }
    }
    if (a.area === 'TESTING_AND_QUALITY_ASSURANCE') {
      return {
        ...a,
        indicator: 'RECURRING_GAP',
        reason: 'Developing or Needs attention in 2 of 2 assessments (2 reviews across 2 repositories), including the latest.',
        evidence: [evidence({ level: 'DEVELOPING' }), evidence({ level: 'NEEDS_ATTENTION', repositoryId: 'repo-2', repositoryName: 'payments', reviewId: 'rev-2' })],
      }
    }
    if (a.area === 'ERROR_HANDLING_AND_RESILIENCE') {
      return {
        ...a,
        indicator: 'MIXED',
        reason: 'No clear pattern: 1 of 2 assessments at Solid or above (2 reviews).',
        projectChanges: [
          {
            repositoryId: 'repo-1',
            repositoryName: 'orders-api',
            from: 'DEVELOPING',
            to: 'SOLID',
            fromCommit: '1111111aaaa',
            toCommit: 'abc1234def5678',
            fromDate: '2026-09-01T10:00:00Z',
            toDate: '2026-10-01T10:00:00Z',
            direction: 'UP',
          },
        ],
        evidence: [evidence(), evidence({ level: 'DEVELOPING', commitSha: '1111111aaaa', reviewId: 'rev-0', date: '2026-09-01T10:00:00Z' })],
      }
    }
    return a
  })
  p.practice = {
    categories: [{ category: 'CONCURRENCY_CONSISTENCY', area: 'CONCURRENCY_AND_CONSISTENCY', answers: 2, verdicts: { STRONG: 1, SOLID: 1 } }],
    roles: [{ value: 'BACKEND_ENGINEER', count: 2 }],
    seniorities: [{ value: 'SDE2', count: 2 }],
    weakButUnpractised: ['TESTING_AND_QUALITY_ASSURANCE'],
  }
  p.recommendations.fromLab = {
    reviewId: null,
    labId: 'lab-1',
    repositoryId: 'repo-1',
    repositoryName: 'orders-api',
    date: '2026-10-02T10:00:00Z',
    topics: [{ topic: 'Idempotency keys', why: 'Retries created duplicates.' }],
  }
  return p
}
