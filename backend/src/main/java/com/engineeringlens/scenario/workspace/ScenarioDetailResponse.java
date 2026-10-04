package com.engineeringlens.scenario.workspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.engineeringlens.scenario.EvaluationStatus;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioDifficulty;
import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;
import com.engineeringlens.scenario.WorkMode;
import com.engineeringlens.scenario.model.ScenarioDocument;

/**
 * A scenario as its workspace shows it: the public document, the starter files with the user's draft, and
 * the draft mode/approach. Never the hidden checks, rubric or reference.
 *
 * @param files empty for approach-only scenarios
 */
public record ScenarioDetailResponse(UUID id, UUID labId, int position, int scenarioCount, String repositoryName, String commitSha,
        String title, ScenarioRole role, Seniority seniority, ScenarioCategory category, ScenarioDifficulty difficulty,
        ExecutionCapability executionCapability, ScenarioLanguage language, ScenarioDocument document, List<FileView> files,
        WorkMode draftMode, String draftApproach, Instant draftUpdatedAt, boolean submitted, EvaluationStatus evaluationStatus) {

    /** @param content the user's draft if any, else the starter */
    public record FileView(String path, boolean editable, String starterContent, String content) {
    }
}
