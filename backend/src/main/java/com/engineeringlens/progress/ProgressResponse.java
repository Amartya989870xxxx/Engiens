package com.engineeringlens.progress;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.progress.Observation.Source;
import com.engineeringlens.progress.ProgressCalculator.Direction;
import com.engineeringlens.progress.ProgressCalculator.Indicator;
import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;

/**
 * The Progress page: evidence-based indicators per engineering area, with the evidence behind each one. Levels are
 * the existing assessment words; there is no score anywhere. No source code, only titles, levels, dates and links.
 *
 * @param scope        what the page covers: one repository, or all of them (repositoryId null)
 * @param repositories every repository with progress evidence, for choosing the scope
 */
public record ProgressResponse(Scope scope, List<RepositoryOption> repositories, EvidenceSummary evidence, List<Area> areas,
        Practice practice, List<NextArea> nextAreas, Recommendations recommendations) {

    public record Scope(UUID repositoryId, String repositoryName) {
    }

    public record RepositoryOption(UUID id, String name) {
    }

    /**
     * @param skipped   stored assessments that couldn't be used (an older review format, or unreadable)
     * @param truncated true when only the newest 50 reviews or labs were used
     */
    public record EvidenceSummary(int reviews, int repositories, int labs, int evaluatedAnswers, Instant from, Instant to, int skipped,
            boolean truncated) {
    }

    public record Area(RubricDimension area, String name, Indicator indicator, String reason, List<ProjectChange> projectChanges,
            List<String> variedOnSameCode, List<EvidenceItem> evidence) {
    }

    /** A better or worse rating of one repository at a later commit: a change in that project's code. */
    public record ProjectChange(UUID repositoryId, String repositoryName, Assessment from, Assessment to, String fromCommit, String toCommit,
            Instant fromDate, Instant toDate, Direction direction) {
    }

    /**
     * One assessment behind an indicator, with a link target: reviewId for a review, labId (and attemptId) for an answer.
     *
     * @param counted false when shown for completeness only (low confidence, or superseded by a later review of the same commit)
     */
    public record EvidenceItem(Source source, Assessment level, Confidence confidence, Instant date, UUID repositoryId, String repositoryName,
            String commitSha, UUID reviewId, UUID labId, UUID attemptId, ScenarioCategory category, String scenarioTitle, boolean counted,
            String note) {
    }

    /** @param weakButUnpractised areas rated Developing or below in the latest review with no Scenario Lab answer yet */
    public record Practice(List<CategoryPractice> categories, List<Count<ScenarioRole>> roles, List<Count<Seniority>> seniorities,
            List<RubricDimension> weakButUnpractised) {
    }

    /** @param verdicts how many answers got each verdict */
    public record CategoryPractice(ScenarioCategory category, RubricDimension area, int answers, Map<Assessment, Integer> verdicts) {
    }

    public record Count<T>(T value, int count) {
    }

    public record NextArea(RubricDimension area, String name, String why) {
    }

    /** Stored teaching, quoted as written (never regenerated): the latest review's and the latest lab's. */
    public record Recommendations(StoredTeaching fromReview, StoredTeaching fromLab) {
    }

    public record StoredTeaching(UUID reviewId, UUID labId, UUID repositoryId, String repositoryName, Instant date, List<Topic> topics) {
    }

    public record Topic(String topic, String why) {
    }
}
