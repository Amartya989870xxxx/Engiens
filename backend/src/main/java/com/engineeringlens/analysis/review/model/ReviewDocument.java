package com.engineeringlens.analysis.review.model;

import java.util.List;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.review.model.ReviewEnums.Applicability;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.analysis.review.model.ReviewEnums.Difficulty;
import com.engineeringlens.analysis.review.model.ReviewEnums.Severity;
import com.engineeringlens.analysis.review.model.ReviewEnums.TradeoffAssessment;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The canonical engineering review (schema v1). Model output is parsed into these records and
 * validated before anything is stored or shown; reviewMetadata and personalization are always set by
 * Engiens, never taken from the model.
 */
public record ReviewDocument(
        @NotNull Integer reviewSchemaVersion,
        ReviewMetadata reviewMetadata,
        @Valid @NotNull OverallAssessment overallAssessment,
        @Valid @NotNull ExecutiveSummary executiveSummary,
        @Valid @NotNull ProjectUnderstanding projectUnderstanding,
        @NotNull @Size(min = 16, max = 16) List<@Valid @NotNull DimensionReview> dimensions,
        @NotNull List<@Valid @NotNull CrossCuttingFinding> crossCuttingFindings,
        @NotNull List<@Valid @NotNull FeatureReview> featureEngineeringReview,
        @Valid @NotNull ScaleReadiness scaleReadiness,
        @NotNull List<@Valid @NotNull PriorityAction> priorityActions,
        @Valid @NotNull LearningPlan personalizedLearningPlan,
        @NotNull List<@Valid @NotNull Highlight> positiveHighlights,
        @NotNull List<@NotBlank String> reviewLimitations,
        Personalization personalization) {

    public static final int SCHEMA_VERSION = 1;

    public ReviewDocument withEngiensFields(ReviewMetadata metadata, Personalization personalization) {
        return new ReviewDocument(reviewSchemaVersion, metadata, overallAssessment, executiveSummary, projectUnderstanding,
                dimensions, crossCuttingFindings, featureEngineeringReview, scaleReadiness, priorityActions,
                personalizedLearningPlan, positiveHighlights, reviewLimitations, personalization);
    }

    /** Set by Engiens from the run and the router, never by the model. */
    public record ReviewMetadata(String repositoryId, String analysisRunId, String reviewRunId, String commitSha, String provider,
            String model, boolean fallbackUsed, int rubricVersion, int contextSchemaVersion, String generatedAt) {
    }

    /** Which teaching level the explanations were written for, and why. Set by Engiens. */
    public record Personalization(String audience, String basis) {
    }

    public record OverallAssessment(@NotNull Assessment level, @NotNull Confidence confidence, @NotBlank String summary,
            @NotNull List<String> strongestAreas, @NotNull List<String> highestPriorityAreas) {
    }

    public record ExecutiveSummary(@NotBlank String whatThisProjectDoes, @NotBlank String engineeringSummary,
            @NotBlank String strongestAspect, @NotBlank String biggestOpportunity, @NotBlank String overallScaleConcern) {
    }

    public record ProjectUnderstanding(@NotBlank String projectType, @NotBlank String architectureSummary,
            @NotNull List<String> detectedStack, @NotNull List<String> importantComponents) {
    }

    public record DimensionReview(@NotNull RubricDimension id, @NotBlank String name, @NotNull Applicability applicability,
            @NotNull Assessment assessment, @NotNull Confidence confidence, @NotBlank String summary,
            @NotNull List<@Valid @NotNull Strength> strengths, @NotNull List<@Valid @NotNull Concern> concerns,
            @NotNull List<@Valid @NotNull Tradeoff> tradeoffs, @NotNull List<String> personalizedAdvice) {
    }

    public record Strength(@NotBlank String title, @NotBlank String description, @NotNull List<@Valid @NotNull Evidence> evidence) {
    }

    public record Concern(@NotBlank String id, @NotBlank String title, @NotNull Severity severity, @NotNull Confidence confidence,
            @NotBlank String description, @NotBlank String whyItMatters, @NotBlank String engineeringImpact,
            @Valid ScaleImpact scaleImpact, @NotNull List<@Valid @NotNull Evidence> evidence, @NotBlank String recommendation,
            String suggestedDirection, @Valid LearningValue learningValue) {
    }

    /** Engineering scenarios, not predictions. Any field may be null when there's no evidence for it. */
    public record ScaleImpact(String currentScale, String tenX, String hundredX, String largeScale, Confidence confidence) {
    }

    public record LearningValue(String currentLevel, String nextLevel, String advancedLevel) {
    }

    public record Tradeoff(@NotBlank String decision, @NotBlank String benefit, @NotBlank String cost,
            @NotNull TradeoffAssessment assessment) {
    }

    /**
     * Points at a file (optionally a line range) or at a deterministic signal. Never both, never neither;
     * line numbers only with a file. Whether they point at something real is checked separately.
     */
    public record Evidence(String file, Integer lineStart, Integer lineEnd, String signalId) {

        @JsonIgnore
        @AssertTrue(message = "evidence must reference exactly one of file or signalId, with valid lines")
        public boolean isWellFormed() {
            boolean hasFile = file != null && !file.isBlank();
            boolean hasSignal = signalId != null && !signalId.isBlank();
            if (hasFile == hasSignal) {
                return false;
            }
            if (!hasFile) {
                return lineStart == null && lineEnd == null;
            }
            if (lineStart == null) {
                return lineEnd == null;
            }
            return lineStart >= 1 && (lineEnd == null || lineEnd >= lineStart);
        }
    }

    public record CrossCuttingFinding(@NotBlank String id, @NotBlank String title, @NotNull RubricDimension category,
            @NotNull Severity severity, @NotNull Confidence confidence, @NotBlank String description,
            @NotBlank String whyItMatters, @NotBlank String engineeringImpact, @NotNull List<@Valid @NotNull Evidence> evidence,
            @Valid ScaleImpact scaleImpact, @NotBlank String recommendation, String exampleApproach) {
    }

    public record FeatureReview(@NotBlank String feature, @Valid @NotNull AssessedSummary correctness,
            @Valid @NotNull AssessedSummary implementationQuality, @Valid @NotNull EdgeCases edgeCases,
            @NotNull List<String> failureModes, @NotNull List<String> scaleConsiderations, @NotNull List<String> recommendations,
            @NotNull List<@Valid @NotNull Evidence> evidence) {
    }

    public record AssessedSummary(@NotNull Assessment assessment, @NotBlank String summary) {
    }

    public record EdgeCases(@NotNull List<String> handled, @NotNull List<String> missing) {
    }

    public record ScaleReadiness(@NotBlank String summary, @Valid @NotNull ScaleArea trafficGrowth, @Valid @NotNull ScaleArea dataGrowth,
            @Valid @NotNull ScaleArea concurrency, @Valid @NotNull ScaleArea failureRecovery,
            @Valid @NotNull ScaleArea operationalComplexity, @NotNull List<@Valid @NotNull Bottleneck> mostLikelyBottlenecks) {
    }

    public record ScaleArea(@NotNull Assessment assessment, @NotNull List<String> concerns) {
    }

    public record Bottleneck(@NotBlank String component, @NotBlank String reason, @NotNull Confidence confidence) {
    }

    public record PriorityAction(@NotNull @Min(1) Integer priority, @NotBlank String title, @NotBlank String reason,
            @NotBlank String expectedBenefit, @NotNull Difficulty difficulty, @NotNull List<RubricDimension> relatedDimensions) {
    }

    public record LearningPlan(@NotNull List<String> youAlreadyDoWell, @NotNull List<@Valid @NotNull LearningTopic> nextThingsToLearn,
            @NotNull List<String> advancedTopics) {
    }

    public record LearningTopic(@NotBlank String topic, @NotBlank String why, @NotBlank String connectionToProject,
            @NotNull @Min(1) Integer suggestedOrder) {
    }

    public record Highlight(@NotBlank String title, @NotBlank String description, @NotBlank String whyThisIsGood,
            @NotNull List<@Valid @NotNull Evidence> evidence) {
    }
}
