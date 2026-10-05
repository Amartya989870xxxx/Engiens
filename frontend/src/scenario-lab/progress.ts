import type { ScenarioLab } from '../api'

/** Honest progress from real events only: the stage, validated scenarios, and replacements. No percentages or estimates. */
export function generationProgress(lab: ScenarioLab) {
  if (lab.generationStage === 'PLANNING' || (lab.generationStage == null && lab.scenariosReady === 0)) {
    return 'Reading the repository and planning scenarios from its code…'
  }
  const ready = `${lab.scenariosReady} of ${lab.scenarioCount} ready`
  return lab.scenariosRejected > 0 ? `${ready} · ${lab.scenariosRejected} replaced after validation` : ready
}
