package com.engineeringlens.scenario.model;

import java.util.List;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The technical verdict on one submitted scenario, from the assessment step (which never sees who the
 * developer is). A qualitative verdict, never a number; test results are evidence for it, not the grade.
 * NOT_ASSESSABLE is for submissions with nothing to judge.
 */
public record ScenarioEvaluation(
        @NotNull Integer scenarioEvaluationSchemaVersion,
        @NotNull Assessment verdict,
        @NotNull Confidence confidence,
        @NotBlank String assessment,
        @NotNull List<@NotBlank String> whatWasCorrect,
        @NotNull List<@NotBlank String> whatWasMissed,
        @NotBlank String rootCause,
        @NotBlank String engineeringJudgment,
        @NotNull List<@NotBlank String> tradeoffs,
        @NotBlank String scaleImpact,
        @NotBlank String regressionRisk,
        @NotBlank String testingAssessment,
        @NotBlank String recommendedFix,
        @NotBlank String referenceApproach) {

    public static final int SCHEMA_VERSION = 1;
}
