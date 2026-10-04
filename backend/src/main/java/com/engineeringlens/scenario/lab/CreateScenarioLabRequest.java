package com.engineeringlens.scenario.lab;

import java.util.List;
import java.util.UUID;

import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Start a lab from exactly one source: a completed review (reviewId), an imported repository
 * (repositoryId), or a GitHub link (repositoryUrl, imported through the normal import pipeline).
 */
public record CreateScenarioLabRequest(
        @Size(max = 300) String repositoryUrl,
        UUID repositoryId,
        UUID reviewId,
        @NotEmpty(message = "Choose at least one role") @Size(max = 5, message = "Choose up to 5 roles")
        List<@NotNull ScenarioRole> roles,
        @NotNull(message = "Choose a seniority") Seniority seniority,
        @NotNull(message = "Choose how many scenarios") Integer scenarioCount) {
}
