package com.engineeringlens.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.progress.ProgressCalculator.AreaProgress;
import com.engineeringlens.progress.ProgressCalculator.Direction;
import com.engineeringlens.progress.ProgressCalculator.Indicator;
import com.engineeringlens.scenario.ScenarioCategory;

/** Every indicator is a deterministic consequence of the stored evidence, and carries its reason and evidence. */
class ProgressCalculatorTest {

    private static final ProgressCalculator CALCULATOR = new ProgressCalculator();
    private static final RubricDimension AREA = RubricDimension.ERROR_HANDLING_AND_RESILIENCE;
    private static final UUID SURGE = UUID.randomUUID();
    private static final UUID ORDERS = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    private static Instant day(int n) {
        return T0.plusSeconds(86_400L * n);
    }

    private static Observation review(UUID repo, String commit, int day, Assessment level) {
        return review(repo, commit, day, level, Confidence.HIGH);
    }

    private static Observation review(UUID repo, String commit, int day, Assessment level, Confidence confidence) {
        return Observation.review(AREA, level, confidence, day(day), repo, repo == SURGE ? "Surge" : "Orders", commit, UUID.randomUUID(), 0);
    }

    private static Observation answer(UUID lab, int day, Assessment level) {
        return Observation.scenario(AREA, level, Confidence.HIGH, day(day), SURGE, "Surge", "c1", lab, UUID.randomUUID(),
                ScenarioCategory.ERROR_HANDLING, "Retry safely", null);
    }

    private static AreaProgress area(Observation... observations) {
        return CALCULATOR.area(AREA, Arrays.asList(observations));
    }

    @Test
    void anAreaWithNoEvidenceIsNotAssessedAndOneOccasionIsNotEnoughHistory() {
        assertThat(area().indicator()).isEqualTo(Indicator.NOT_ASSESSED);

        AreaProgress once = area(review(SURGE, "c1", 0, Assessment.DEVELOPING));
        assertThat(once.indicator()).isEqualTo(Indicator.NOT_ENOUGH_HISTORY);
        assertThat(once.reason()).isEqualTo("Assessed on one occasion (1 review). Not enough history to identify a trend.");
        assertThat(once.evidence()).hasSize(1);
    }

    @Test
    void regeneratedReviewsOfTheSameCommitAreOneOccasionAndTheirDisagreementIsFlaggedNotCountedAsProgress() {
        AreaProgress area = area(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(SURGE, "c1", 1, Assessment.STRONG));

        assertThat(area.indicator()).isEqualTo(Indicator.NOT_ENOUGH_HISTORY);
        assertThat(area.variedOnSameCode()).containsExactly("Surge");
        assertThat(area.projectChanges()).isEmpty();
        assertThat(area.evidence()).extracting(ProgressCalculator.Evidence::counted).containsExactly(true, false); // newest first
        assertThat(area.evidence().get(1).note()).isEqualTo("Superseded by a later review of the same commit.");
    }

    @Test
    void lowConfidenceAssessmentsAreShownButNeverEnoughOnTheirOwn() {
        AreaProgress area = area(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(SURGE, "c2", 5, Assessment.DEVELOPING, Confidence.LOW));
        assertThat(area.indicator()).isEqualTo(Indicator.NOT_ENOUGH_HISTORY);
        assertThat(area.reason()).contains("1 low-confidence assessment is shown but not counted");
        assertThat(area.evidence()).hasSize(2);
    }

    @Test
    void aRecurringGapNeedsTwoThirdsOfAssessmentsWeakIncludingTheLatest() {
        AreaProgress gap = area(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(ORDERS, "o1", 1, Assessment.SOLID),
                review(ORDERS, "o2", 2, Assessment.NEEDS_ATTENTION));
        assertThat(gap.indicator()).isEqualTo(Indicator.RECURRING_GAP);
        assertThat(gap.reason()).isEqualTo("Developing or Needs attention in 2 of 3 assessments (3 reviews across 2 repositories), including the latest.");

        // Same counts, but the latest is Solid: not a recurring gap.
        AreaProgress recovered = area(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(ORDERS, "o1", 1, Assessment.NEEDS_ATTENTION),
                review(SURGE, "c2", 2, Assessment.SOLID));
        assertThat(recovered.indicator()).isNotEqualTo(Indicator.RECURRING_GAP);
    }

    @Test
    void aConsistentStrengthNeedsTwoThirdsSolidOrBetterIncludingTheLatest() {
        UUID lab = UUID.randomUUID();
        AreaProgress strength = area(review(SURGE, "c1", 0, Assessment.SOLID), answer(lab, 1, Assessment.DEVELOPING),
                answer(lab, 2, Assessment.STRONG));
        assertThat(strength.indicator()).isEqualTo(Indicator.CONSISTENT_STRENGTH);
        assertThat(strength.reason()).isEqualTo("Solid or Strong in 2 of 3 assessments (1 review and 2 Scenario Lab answers), including the latest.");
    }

    @Test
    void aSpreadOfTwoLevelsWithoutAPatternIsInconsistentAndANarrowOneIsMixed() {
        AreaProgress inconsistent = area(review(SURGE, "c1", 0, Assessment.STRONG), review(ORDERS, "o1", 1, Assessment.NEEDS_ATTENTION),
                review(SURGE, "c2", 2, Assessment.SOLID), review(ORDERS, "o2", 3, Assessment.DEVELOPING));
        assertThat(inconsistent.indicator()).isEqualTo(Indicator.INCONSISTENT);
        assertThat(inconsistent.reason()).startsWith("Ranged from Needs attention to Strong across 4 assessments");

        AreaProgress mixed = area(review(SURGE, "c1", 0, Assessment.SOLID), review(ORDERS, "o1", 1, Assessment.DEVELOPING));
        assertThat(mixed.indicator()).isEqualTo(Indicator.MIXED);
    }

    @Test
    void aBetterRatingAtALaterCommitOfOneRepositoryIsAProjectLevelChangeNotDeveloperImprovement() {
        AreaProgress area = area(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(SURGE, "c2", 7, Assessment.SOLID));

        assertThat(area.indicator()).isNotEqualTo(Indicator.IMPROVING);
        assertThat(area.projectChanges()).singleElement().satisfies(c -> {
            assertThat(c.repositoryName()).isEqualTo("Surge");
            assertThat(c.from()).isEqualTo(Assessment.DEVELOPING);
            assertThat(c.to()).isEqualTo(Assessment.SOLID);
            assertThat(c.fromCommit()).isEqualTo("c1");
            assertThat(c.toCommit()).isEqualTo("c2");
            assertThat(c.direction()).isEqualTo(Direction.UP);
        });
    }

    @Test
    void developerImprovementNeedsGainsInSeveralRepositoriesWithNoDecline() {
        AreaProgress twoRepos = area(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(SURGE, "c2", 5, Assessment.SOLID),
                review(ORDERS, "o1", 1, Assessment.NEEDS_ATTENTION), review(ORDERS, "o2", 6, Assessment.DEVELOPING));
        assertThat(twoRepos.indicator()).isEqualTo(Indicator.IMPROVING);
        assertThat(twoRepos.reason()).isEqualTo("Rated higher at a later commit in 2 repositories (Surge, Orders), with no decline elsewhere.");

        AreaProgress oneDeclined = area(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(SURGE, "c2", 5, Assessment.SOLID),
                review(ORDERS, "o1", 1, Assessment.SOLID), review(ORDERS, "o2", 6, Assessment.DEVELOPING));
        assertThat(oneDeclined.indicator()).isNotEqualTo(Indicator.IMPROVING);
        assertThat(oneDeclined.projectChanges()).extracting(ProgressCalculator.ProjectChange::direction)
                .containsExactlyInAnyOrder(Direction.UP, Direction.DOWN);
    }

    @Test
    void developerImprovementFromScenarioLabNeedsFourAnswersAcrossTwoLabs() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AreaProgress improving = area(answer(first, 0, Assessment.DEVELOPING), answer(first, 0, Assessment.NEEDS_ATTENTION),
                answer(second, 9, Assessment.SOLID), answer(second, 9, Assessment.STRONG));
        assertThat(improving.indicator()).isEqualTo(Indicator.IMPROVING);
        assertThat(improving.reason()).isEqualTo("Your Scenario Lab answers moved from mostly Needs attention to mostly Solid across 2 labs (4 answers).");

        // The same verdicts inside one lab are a single occasion's work, not a trend.
        UUID only = UUID.randomUUID();
        AreaProgress oneLab = area(answer(only, 0, Assessment.DEVELOPING), answer(only, 0, Assessment.NEEDS_ATTENTION),
                answer(only, 1, Assessment.SOLID), answer(only, 1, Assessment.STRONG));
        assertThat(oneLab.indicator()).isEqualTo(Indicator.NOT_ENOUGH_HISTORY);

        // Three answers across two labs: too few for a trend.
        AreaProgress few = area(answer(first, 0, Assessment.DEVELOPING), answer(second, 9, Assessment.SOLID), answer(second, 9, Assessment.STRONG));
        assertThat(few.indicator()).isNotEqualTo(Indicator.IMPROVING);
    }

    @Test
    void evidenceIsNewestFirstAndEveryAreaIsReportedOnTheSixteenDimensions() {
        AreaProgress area = area(review(SURGE, "c1", 0, Assessment.SOLID), review(ORDERS, "o1", 3, Assessment.SOLID));
        assertThat(area.evidence()).extracting(e -> e.observation().at()).containsExactly(day(3), day(0));

        assertThat(CALCULATOR.areas(List.of(review(SURGE, "c1", 0, Assessment.SOLID)))).hasSize(16)
                .extracting(AreaProgress::area).containsExactly(RubricDimension.values());
    }

    @Test
    void nextAreasPutRecurringGapsFirstThenWeakUnpractisedAreasThenWeakAnswersAndStopAtFour() {
        List<Observation> all = new ArrayList<>();
        // Security: a recurring gap with HIGH concerns; Testing: a recurring gap without.
        all.add(Observation.review(RubricDimension.TESTING_AND_QUALITY_ASSURANCE, Assessment.DEVELOPING, Confidence.HIGH, day(0), SURGE, "Surge", "c1", UUID.randomUUID(), 0));
        all.add(Observation.review(RubricDimension.TESTING_AND_QUALITY_ASSURANCE, Assessment.DEVELOPING, Confidence.HIGH, day(2), ORDERS, "Orders", "o1", UUID.randomUUID(), 0));
        all.add(Observation.review(RubricDimension.SECURITY, Assessment.NEEDS_ATTENTION, Confidence.HIGH, day(0), SURGE, "Surge", "c1", UUID.randomUUID(), 1));
        all.add(Observation.review(RubricDimension.SECURITY, Assessment.DEVELOPING, Confidence.HIGH, day(2), ORDERS, "Orders", "o1", UUID.randomUUID(), 2));
        // Data: weak in the latest review and never practised.
        all.add(Observation.review(RubricDimension.DATA_AND_PERSISTENCE, Assessment.DEVELOPING, Confidence.HIGH, day(2), ORDERS, "Orders", "o1", UUID.randomUUID(), 0));
        // Concurrency: weak in review but practised, and the latest answer was weak.
        all.add(Observation.review(RubricDimension.CONCURRENCY_AND_CONSISTENCY, Assessment.SOLID, Confidence.HIGH, day(2), ORDERS, "Orders", "o1", UUID.randomUUID(), 0));
        all.add(Observation.scenario(RubricDimension.CONCURRENCY_AND_CONSISTENCY, Assessment.DEVELOPING, Confidence.HIGH, day(3), SURGE, "Surge", "c1", UUID.randomUUID(), UUID.randomUUID(), ScenarioCategory.CONCURRENCY_CONSISTENCY, "t", null));
        // Performance: weak and unpractised too, but past the limit of four.
        all.add(Observation.review(RubricDimension.PERFORMANCE_AND_EFFICIENCY, Assessment.DEVELOPING, Confidence.HIGH, day(2), ORDERS, "Orders", "o1", UUID.randomUUID(), 0));

        List<ProgressCalculator.NextArea> next = CALCULATOR.nextAreas(CALCULATOR.areas(all));

        assertThat(next).extracting(ProgressCalculator.NextArea::area).containsExactly(RubricDimension.SECURITY,
                RubricDimension.TESTING_AND_QUALITY_ASSURANCE, RubricDimension.DATA_AND_PERSISTENCE, RubricDimension.PERFORMANCE_AND_EFFICIENCY);
        assertThat(next.get(0).why()).startsWith("A recurring gap: Developing or Needs attention in 2 of 2 assessments");
        assertThat(next.get(2).why()).isEqualTo("Rated Developing in your latest review of Orders, and not practised in Scenario Lab yet.");
        assertThat(CALCULATOR.weakButUnpractised(CALCULATOR.areas(all))).extracting(AreaProgress::area)
                .contains(RubricDimension.DATA_AND_PERSISTENCE).doesNotContain(RubricDimension.CONCURRENCY_AND_CONSISTENCY);
    }

    @Test
    void theSameEvidenceAlwaysGivesTheSameResult() {
        List<Observation> evidence = List.of(review(SURGE, "c1", 0, Assessment.DEVELOPING), review(ORDERS, "o1", 1, Assessment.NEEDS_ATTENTION));
        assertThat(CALCULATOR.areas(evidence)).isEqualTo(CALCULATOR.areas(new ArrayList<>(evidence).reversed()));
    }
}
