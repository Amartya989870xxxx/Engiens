package com.engineeringlens.scenario.lab;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.engineeringlens.scenario.EvaluationStatus;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioDifficulty;
import com.engineeringlens.scenario.ScenarioLabStatus;
import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;

/**
 * A lab's snapshot, status and progress. {@code scenarios} is empty while generating: scenarios are shown
 * only once all of them are validated. Never contains hidden checks or reference solutions.
 */
public record ScenarioLabResponse(UUID id, UUID repositoryId, String repositoryName, String repositoryUrl, UUID reviewId,
        String commitSha, List<ScenarioRole> roles, Seniority seniority, int scenarioCount, int scenariosReady,
        ScenarioLabStatus status, String errorCode, String errorMessage, Instant createdAt, Instant completedAt,
        List<ScenarioSummary> scenarios) {

    /** @param evaluationStatus null until the scenario is submitted */
    public record ScenarioSummary(UUID id, int position, String title, ScenarioRole role, ScenarioCategory category,
            ScenarioDifficulty difficulty, ExecutionCapability executionCapability, ScenarioLanguage language, boolean submitted,
            EvaluationStatus evaluationStatus) {
    }
}
