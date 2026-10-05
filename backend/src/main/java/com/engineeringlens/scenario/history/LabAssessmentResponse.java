package com.engineeringlens.scenario.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioDifficulty;
import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;
import com.engineeringlens.scenario.WorkMode;
import com.engineeringlens.scenario.execution.RunResult;
import com.engineeringlens.scenario.model.LabAssessment;
import com.engineeringlens.scenario.model.ScenarioDocument;
import com.engineeringlens.scenario.model.ScenarioEvaluation;
import com.engineeringlens.scenario.model.ScenarioReference;
import com.engineeringlens.scenario.workspace.FileContent;

/**
 * A completed lab, read-only: the snapshot, the overall assessment, and every scenario with what was submitted,
 * the objective results, the evaluation and (now that it's over) the reference. Never the hidden checks' source.
 */
public record LabAssessmentResponse(UUID id, int number, UUID repositoryId, String repositoryName, String repositoryUrl, UUID reviewId,
        String commitSha, List<ScenarioRole> roles, Seniority seniority, int scenarioCount, Instant createdAt, Instant completedAt,
        LabAssessment assessment, List<ScenarioResult> scenarios, int scenariosGenerated, String generationNote) {

    public record ScenarioResult(UUID scenarioId, int position, String title, ScenarioRole role, ScenarioCategory category,
            ScenarioDifficulty difficulty, ExecutionCapability executionCapability, ScenarioLanguage language, ScenarioDocument document,
            WorkMode mode, List<FileContent> submittedFiles, String submittedApproach, RunResult runResult, ScenarioEvaluation evaluation,
            ScenarioReference reference, List<String> learningPoints, Instant submittedAt) {
    }
}
