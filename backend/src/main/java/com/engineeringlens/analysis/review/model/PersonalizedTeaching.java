package com.engineeringlens.analysis.review.model;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What the personalisation step may write: advice per dimension and the learning plan. Nothing else.
 * Assessments, severities and findings aren't in this type, so personalisation can't change them.
 */
public record PersonalizedTeaching(
        @NotNull List<@Valid @NotNull DimensionAdvice> dimensions,
        @Valid @NotNull ReviewDocument.LearningPlan personalizedLearningPlan) {

    public record DimensionAdvice(@NotNull RubricDimension id, @NotNull List<@NotBlank String> personalizedAdvice) {
    }
}
