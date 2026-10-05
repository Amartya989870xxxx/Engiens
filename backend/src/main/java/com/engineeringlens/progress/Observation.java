package com.engineeringlens.progress;

import java.time.Instant;
import java.util.UUID;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.scenario.ScenarioCategory;

/**
 * One stored assessment of one engineering area: a review's rating of a dimension (the code in a repository at a
 * commit), or a Scenario Lab verdict on the developer's own answer. Progress is calculated from these only.
 *
 * @param highSeverityConcerns reviews only: HIGH-severity concerns the review raised in this dimension
 * @param note                 how the observation was mapped or treated, shown with the evidence (may be null)
 */
public record Observation(RubricDimension area, Source source, Assessment level, Confidence confidence, Instant at,
        UUID repositoryId, String repositoryName, String commitSha, UUID reviewId, UUID labId, UUID attemptId,
        ScenarioCategory category, String scenarioTitle, int highSeverityConcerns, String note) {

    public enum Source {
        /** An engineering review: assesses the code in a repository, which may not all be the developer's own. */
        REVIEW,
        /** A Scenario Lab verdict: assesses the developer's own answer. */
        SCENARIO
    }

    static Observation review(RubricDimension area, Assessment level, Confidence confidence, Instant at, UUID repositoryId,
            String repositoryName, String commitSha, UUID reviewId, int highSeverityConcerns) {
        return new Observation(area, Source.REVIEW, level, confidence, at, repositoryId, repositoryName, commitSha, reviewId, null, null,
                null, null, highSeverityConcerns, null);
    }

    static Observation scenario(RubricDimension area, Assessment level, Confidence confidence, Instant at, UUID repositoryId,
            String repositoryName, String commitSha, UUID labId, UUID attemptId, ScenarioCategory category, String title, String note) {
        return new Observation(area, Source.SCENARIO, level, confidence, at, repositoryId, repositoryName, commitSha, null, labId,
                attemptId, category, title, 0, note);
    }
}
