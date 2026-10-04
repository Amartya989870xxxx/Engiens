package com.engineeringlens.scenario.model;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The personalised part, from the teaching step (profile + final verdicts, no code). It has no field for a
 * verdict, so even a model that tries to regrade can't: it only adds learning points and recommendations.
 */
public record LabTeaching(@NotNull List<@Valid @NotNull ScenarioLearning> scenarioLearning,
        @NotNull List<@Valid @NotNull LearningRecommendation> learningRecommendations) {

    public record ScenarioLearning(@NotBlank String scenarioId, @NotNull List<@NotBlank String> learningPoints) {
    }

    public record LearningRecommendation(@NotBlank String topic, @NotBlank String why, @NotBlank String connectionToProject) {
    }
}
