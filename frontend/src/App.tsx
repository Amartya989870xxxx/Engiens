import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from './auth'
import { Layout, ProtectedRoute, RequireNoProfile, RequireProfile } from './components'
import { AuthPage } from './pages/AuthPage'
import { DashboardPage } from './pages/DashboardPage'
import { LandingPage } from './pages/LandingPage'
import { AppShell } from './shell/AppShell'
import { OnboardingPage } from './pages/OnboardingPage'
import { ProfilePage } from './pages/ProfilePage'
import { RepositoryPage } from './pages/RepositoryPage'
import { ReviewPage } from './pages/ReviewPage'
import { LabAssessmentPage } from './scenario-history/LabAssessmentPage'
import { ScenarioFeedbackPage } from './scenario-history/ScenarioFeedbackPage'
import { LabCompletePage } from './scenario-lab/LabCompletePage'
import { ScenarioLabPage } from './scenario-lab/ScenarioLabPage'

// The workspace carries the code editor: load it only when a scenario is opened.
const ScenarioWorkspacePage = lazy(() => import('./scenario-lab/ScenarioWorkspacePage').then((m) => ({ default: m.ScenarioWorkspacePage })))

const queryClient = new QueryClient()

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<LandingPage />} />
            <Route path="/login" element={<AuthPage mode="login" />} />
            <Route path="/register" element={<AuthPage mode="register" />} />
            <Route element={<ProtectedRoute />}>
              {/* Onboarding keeps a simple header: it's a focused flow, not part of the main app yet. */}
              <Route element={<RequireNoProfile />}>
                <Route element={<Layout />}>
                  <Route path="/onboarding" element={<OnboardingPage />} />
                </Route>
              </Route>
              <Route element={<RequireProfile />}>
                <Route element={<AppShell />}>
                  <Route path="/dashboard" element={<DashboardPage />} />
                  <Route path="/profile" element={<ProfilePage />} />
                  <Route path="/repositories/:id" element={<RepositoryPage />} />
                  <Route path="/reviews/:id" element={<ReviewPage />} />
                  <Route path="/scenario-lab" element={<ScenarioLabPage />} />
                  <Route
                    path="/scenario-lab/:labId/scenarios/:scenarioId"
                    element={
                      <Suspense fallback={<p className="px-6 py-10 text-sm text-muted">Loading scenario…</p>}>
                        <ScenarioWorkspacePage />
                      </Suspense>
                    }
                  />
                  <Route path="/scenario-lab/:labId/scenarios/:scenarioId/feedback" element={<ScenarioFeedbackPage />} />
                  <Route path="/scenario-lab/complete/:labId" element={<LabCompletePage />} />
                  <Route path="/repositories/:repositoryId/scenario-labs/:labId" element={<LabAssessmentPage />} />
                </Route>
              </Route>
            </Route>
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}
