import type { DimensionReview, ReviewDocument, ReviewRun } from '../api'

const dimension = (id: string, name: string, extra: Partial<DimensionReview> = {}): DimensionReview => ({
  id, name, applicability: 'APPLICABLE', assessment: 'SOLID', confidence: 'MEDIUM',
  summary: `${name} summary.`, strengths: [], concerns: [], tradeoffs: [], personalizedAdvice: [], ...extra,
})

/** A small but complete review: enough to exercise every section of the report. */
export const reviewDocument: ReviewDocument = {
  reviewSchemaVersion: 1,
  reviewMetadata: null,
  overallAssessment: { level: 'DEVELOPING', confidence: 'MEDIUM', summary: 'A clear API with thin tests.', strongestAreas: ['API design'], highestPriorityAreas: ['Testing'] },
  executiveSummary: {
    whatThisProjectDoes: 'An order-taking API.', engineeringSummary: 'Routes and persistence are separated.',
    strongestAspect: 'Clear routing.', biggestOpportunity: 'Test the checkout path.', overallScaleConcern: 'Unbounded list queries.',
  },
  projectUnderstanding: { projectType: 'Backend API', architectureSummary: 'Layered.', detectedStack: ['FastAPI'], importantComponents: ['orders'] },
  dimensions: [
    dimension('ERROR_HANDLING', 'Error handling', {
      assessment: 'NEEDS_ATTENTION',
      concerns: [{
        id: 'EH-1', title: 'Database errors are swallowed', severity: 'HIGH', confidence: 'HIGH',
        description: 'The order route catches every exception and returns 200.', whyItMatters: 'Failures look like successes.',
        engineeringImpact: 'Lost orders go unnoticed.', scaleImpact: null,
        evidence: [{ file: 'app/orders.py', lineStart: 12, lineEnd: 14 }, { signalId: 'ERR_BROAD_EXCEPT' }],
        recommendation: 'Let errors reach a central handler.', suggestedDirection: null, learningValue: null,
      }],
      personalizedAdvice: ['At your stage, start by never returning success from an except block.'],
    }),
    dimension('TESTING', 'Testing'),
    dimension('OBSERVABILITY', 'Observability', { applicability: 'NOT_APPLICABLE', assessment: 'NOT_ASSESSABLE', summary: 'Nothing to assess here.' }),
  ],
  crossCuttingFindings: [],
  featureEngineeringReview: [],
  scaleReadiness: {
    summary: 'Fine for a class project.',
    trafficGrowth: { assessment: 'DEVELOPING', concerns: [] }, dataGrowth: { assessment: 'DEVELOPING', concerns: [] },
    concurrency: { assessment: 'NOT_ASSESSABLE', concerns: [] }, failureRecovery: { assessment: 'DEVELOPING', concerns: [] },
    operationalComplexity: { assessment: 'SOLID', concerns: [] }, mostLikelyBottlenecks: [],
  },
  priorityActions: [{ priority: 1, title: 'Add a checkout test', reason: 'Money path.', expectedBenefit: 'Confidence.', difficulty: 'LOW', relatedDimensions: ['TESTING'] }],
  personalizedLearningPlan: { youAlreadyDoWell: ['Routing'], nextThingsToLearn: [], advancedTopics: [] },
  positiveHighlights: [],
  reviewLimitations: ['Only selected files were read.'],
  personalization: { audience: 'FOUNDATION', basis: 'Undergraduate, year 2' },
}

export const reviewRun = (overrides: Partial<ReviewRun> = {}): ReviewRun => ({
  id: 'rev-1', repositoryId: 'repo-1', repositoryName: 'orders-api', analysisRunId: 'run-1', status: 'COMPLETED',
  commitSha: 'c0ffee1234567890', provider: 'gemini', model: 'gemini-3.8-flash', fallbackUsed: false,
  errorCode: null, errorMessage: null, createdAt: '2026-10-04T10:00:00Z', completedAt: '2026-10-04T10:02:00Z', durationMs: 120_000,
  review: reviewDocument, ...overrides,
})
