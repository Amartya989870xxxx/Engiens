package com.engineeringlens.progress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.engineeringlens.analysis.common.Confidence;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.progress.Observation.Source;

/**
 * Turns stored assessments into evidence-based indicators per engineering area. Deterministic and free of I/O: the
 * same evidence always gives the same result, and every indicator carries the reason and evidence behind it.
 *
 * <p>The indicators describe the stored evidence; they don't measure overall engineering ability. Two kinds of
 * change are kept apart: a <em>project-level change</em> (the same repository rated differently at a later commit:
 * the code changed) and <em>improving</em> (the developer's own work getting better, which needs several
 * assessments: Scenario Lab answers across labs, or project-level gains in more than one repository).
 */
public final class ProgressCalculator {

    /** Fewer distinct occasions than this (review commits plus labs) is not enough history for an indicator. */
    static final int MIN_OCCASIONS = 2;
    /** Share of counted assessments that must agree for a consistent strength or a recurring gap. */
    static final double AGREEMENT = 2.0 / 3.0;
    /** A Scenario Lab trend needs this many counted answers ... */
    static final int MIN_SCENARIO_ANSWERS_FOR_TREND = 4;
    /** ... spread over at least this many labs. */
    static final int MIN_LABS_FOR_TREND = 2;
    /** Project-level gains count as the developer improving only when seen in this many repositories, with no declines. */
    static final int MIN_REPOSITORIES_FOR_IMPROVEMENT = 2;
    static final int MAX_NEXT_AREAS = 4;

    public enum Indicator {
        /** No assessment of this area yet. */
        NOT_ASSESSED,
        /** Assessed, but too few occasions to say anything about a pattern. */
        NOT_ENOUGH_HISTORY,
        IMPROVING,
        RECURRING_GAP,
        CONSISTENT_STRENGTH,
        INCONSISTENT,
        /** Enough evidence, but no clear pattern. */
        MIXED
    }

    public enum Direction {
        UP, DOWN
    }

    /** An observation as shown in an area's evidence, and whether it counted towards the indicator. */
    public record Evidence(Observation observation, boolean counted, String note) {
    }

    /** The same repository rated differently at a later commit: a change in the project, not a measure of the developer. */
    public record ProjectChange(UUID repositoryId, String repositoryName, Assessment from, Assessment to, String fromCommit,
            String toCommit, Instant fromDate, Instant toDate, Direction direction) {
    }

    /**
     * @param variedOnSameCode repositories where reviews of the same commit disagreed (model variance, not progress)
     * @param evidence         newest first
     */
    public record AreaProgress(RubricDimension area, Indicator indicator, String reason, List<ProjectChange> projectChanges,
            List<String> variedOnSameCode, List<Evidence> evidence) {
    }

    public record NextArea(RubricDimension area, String why) {
    }

    public List<AreaProgress> areas(List<Observation> observations) {
        List<AreaProgress> areas = new ArrayList<>();
        for (RubricDimension area : RubricDimension.values()) {
            areas.add(area(area, observations.stream().filter(o -> o.area() == area).toList()));
        }
        return areas;
    }

    AreaProgress area(RubricDimension area, List<Observation> observations) {
        if (observations.isEmpty()) {
            return new AreaProgress(area, Indicator.NOT_ASSESSED, "Not assessed yet.", List.of(), List.of(), List.of());
        }
        List<Evidence> evidence = new ArrayList<>();
        Set<String> varied = new java.util.TreeSet<>();

        // Several reviews of the same code (a regenerated review) are one occasion: the latest one counts.
        Map<String, List<Observation>> sameCode = observations.stream().filter(o -> o.source() == Source.REVIEW)
                .sorted(Comparator.comparing(Observation::at))
                .collect(Collectors.groupingBy(ProgressCalculator::codeKey, LinkedHashMap::new, Collectors.toList()));
        for (List<Observation> reviews : sameCode.values()) {
            Observation latest = reviews.get(reviews.size() - 1);
            if (reviews.stream().map(Observation::level).distinct().count() > 1) {
                varied.add(latest.repositoryName());
            }
            for (Observation o : reviews) {
                if (o != latest) {
                    evidence.add(new Evidence(o, false, "Superseded by a later review of the same commit."));
                }
            }
            evidence.add(countIfConfident(latest));
        }
        observations.stream().filter(o -> o.source() == Source.SCENARIO).forEach(o -> evidence.add(countIfConfident(o)));
        evidence.sort(Comparator.comparing((Evidence e) -> e.observation().at()).reversed());

        List<Observation> counted = evidence.stream().filter(Evidence::counted).map(Evidence::observation)
                .sorted(Comparator.comparing(Observation::at)).toList();
        List<ProjectChange> changes = projectChanges(counted);
        long notCountedLowConfidence = evidence.stream().filter(e -> !e.counted() && e.observation().confidence() == Confidence.LOW).count();

        int occasions = occasions(counted);
        if (occasions < MIN_OCCASIONS) {
            String once = counted.isEmpty() ? "No high- or medium-confidence assessment yet." : "Assessed on one occasion ("
                    + describe(counted) + ").";
            return new AreaProgress(area, Indicator.NOT_ENOUGH_HISTORY, once + lowConfidenceNote(notCountedLowConfidence)
                    + " Not enough history to identify a trend.", changes, List.copyOf(varied), evidence);
        }

        String improving = improving(counted, changes);
        if (improving != null) {
            return new AreaProgress(area, Indicator.IMPROVING, improving, changes, List.copyOf(varied), evidence);
        }
        Observation latest = counted.get(counted.size() - 1);
        long weak = counted.stream().filter(o -> rank(o.level()) <= rank(Assessment.DEVELOPING)).count();
        long strong = counted.stream().filter(o -> rank(o.level()) >= rank(Assessment.SOLID)).count();
        if (weak >= AGREEMENT * counted.size() && rank(latest.level()) <= rank(Assessment.DEVELOPING)) {
            return new AreaProgress(area, Indicator.RECURRING_GAP, "Developing or Needs attention in " + weak + " of " + counted.size()
                    + " assessments (" + describe(counted) + "), including the latest.", changes, List.copyOf(varied), evidence);
        }
        if (strong >= AGREEMENT * counted.size() && rank(latest.level()) >= rank(Assessment.SOLID)) {
            return new AreaProgress(area, Indicator.CONSISTENT_STRENGTH, "Solid or Strong in " + strong + " of " + counted.size()
                    + " assessments (" + describe(counted) + "), including the latest.", changes, List.copyOf(varied), evidence);
        }
        int lowest = counted.stream().mapToInt(o -> rank(o.level())).min().orElseThrow();
        int highest = counted.stream().mapToInt(o -> rank(o.level())).max().orElseThrow();
        if (highest - lowest >= 2) {
            return new AreaProgress(area, Indicator.INCONSISTENT, "Ranged from " + label(lowest) + " to " + label(highest) + " across "
                    + counted.size() + " assessments (" + describe(counted) + ").", changes, List.copyOf(varied), evidence);
        }
        return new AreaProgress(area, Indicator.MIXED, "No clear pattern: " + strong + " of " + counted.size()
                + " assessments at Solid or above (" + describe(counted) + ").", changes, List.copyOf(varied), evidence);
    }

    /**
     * Developer-level improvement, from either kind of evidence: Scenario Lab answers getting better across labs, or the
     * same kind of project-level gain in several repositories. Null when neither holds.
     */
    private static String improving(List<Observation> counted, List<ProjectChange> changes) {
        List<Observation> answers = counted.stream().filter(o -> o.source() == Source.SCENARIO).toList();
        long labs = answers.stream().map(Observation::labId).distinct().count();
        if (answers.size() >= MIN_SCENARIO_ANSWERS_FOR_TREND && labs >= MIN_LABS_FOR_TREND) {
            int half = answers.size() / 2;
            int earlier = lowerMedian(answers.subList(0, half));
            int later = lowerMedian(answers.subList(answers.size() - half, answers.size()));
            if (later > earlier) {
                return "Your Scenario Lab answers moved from mostly " + label(earlier) + " to mostly " + label(later) + " across " + labs
                        + " labs (" + answers.size() + " answers).";
            }
        }
        long up = changes.stream().filter(c -> c.direction() == Direction.UP).map(ProjectChange::repositoryId).distinct().count();
        boolean anyDown = changes.stream().anyMatch(c -> c.direction() == Direction.DOWN);
        if (up >= MIN_REPOSITORIES_FOR_IMPROVEMENT && !anyDown) {
            return "Rated higher at a later commit in " + up + " repositories ("
                    + changes.stream().map(ProjectChange::repositoryName).distinct().collect(Collectors.joining(", "))
                    + "), with no decline elsewhere.";
        }
        return null;
    }

    /** Per repository: the earliest and latest counted review at different commits, when their ratings differ. */
    private static List<ProjectChange> projectChanges(List<Observation> counted) {
        Map<UUID, List<Observation>> byRepository = counted.stream().filter(o -> o.source() == Source.REVIEW)
                .collect(Collectors.groupingBy(Observation::repositoryId, LinkedHashMap::new, Collectors.toList()));
        List<ProjectChange> changes = new ArrayList<>();
        for (List<Observation> reviews : byRepository.values()) {
            if (reviews.size() < 2) {
                continue;
            }
            Observation first = reviews.get(0);
            Observation last = reviews.get(reviews.size() - 1);
            if (first.level() != last.level()) {
                changes.add(new ProjectChange(last.repositoryId(), last.repositoryName(), first.level(), last.level(), first.commitSha(),
                        last.commitSha(), first.at(), last.at(), rank(last.level()) > rank(first.level()) ? Direction.UP : Direction.DOWN));
            }
        }
        return changes;
    }

    /**
     * What to work on next, in this order: recurring gaps (more HIGH-severity concerns first), areas rated weak in the
     * latest review and not practised yet, then areas whose latest Scenario Lab answer was weak. At most four.
     */
    public List<NextArea> nextAreas(List<AreaProgress> areas) {
        Map<RubricDimension, NextArea> next = new LinkedHashMap<>();
        areas.stream().filter(a -> a.indicator() == Indicator.RECURRING_GAP)
                .sorted(Comparator.comparingInt((AreaProgress a) -> -latest(a, Source.REVIEW).map(Observation::highSeverityConcerns).orElse(0))
                        .thenComparing(AreaProgress::area))
                .forEach(a -> next.putIfAbsent(a.area(), new NextArea(a.area(), "A recurring gap: " + a.reason())));
        for (AreaProgress a : weakButUnpractised(areas)) {
            Observation review = latest(a, Source.REVIEW).orElseThrow();
            next.putIfAbsent(a.area(), new NextArea(a.area(), "Rated " + label(rank(review.level())) + " in your latest review of "
                    + review.repositoryName() + ", and not practised in Scenario Lab yet."));
        }
        for (AreaProgress a : areas) {
            latest(a, Source.SCENARIO).filter(o -> rank(o.level()) <= rank(Assessment.DEVELOPING)).ifPresent(o -> next.putIfAbsent(a.area(),
                    new NextArea(a.area(), "Your latest Scenario Lab answer in this area was rated " + label(rank(o.level())) + ".")));
        }
        return next.values().stream().limit(MAX_NEXT_AREAS).toList();
    }

    /** Areas whose latest counted review is Developing or below, with no Scenario Lab answer in them at all. */
    public List<AreaProgress> weakButUnpractised(List<AreaProgress> areas) {
        return areas.stream()
                .filter(a -> a.evidence().stream().noneMatch(e -> e.observation().source() == Source.SCENARIO))
                .filter(a -> latest(a, Source.REVIEW).filter(o -> rank(o.level()) <= rank(Assessment.DEVELOPING)).isPresent())
                .toList();
    }

    private static java.util.Optional<Observation> latest(AreaProgress area, Source source) {
        return area.evidence().stream().filter(Evidence::counted).map(Evidence::observation).filter(o -> o.source() == source)
                .max(Comparator.comparing(Observation::at));
    }

    private static Evidence countIfConfident(Observation o) {
        return o.confidence() == Confidence.LOW ? new Evidence(o, false, "Low confidence: shown, but not counted.") : new Evidence(o, true, null);
    }

    /** Distinct occasions: each reviewed commit of each repository, and each lab. */
    private static int occasions(List<Observation> counted) {
        Set<String> occasions = new HashSet<>();
        counted.forEach(o -> occasions.add(o.source() == Source.REVIEW ? "R:" + codeKey(o) : "L:" + o.labId()));
        return occasions.size();
    }

    private static String codeKey(Observation review) {
        return review.repositoryId() + "@" + (review.commitSha() != null ? review.commitSha() : review.reviewId());
    }

    private static String describe(List<Observation> counted) {
        long reviews = counted.stream().filter(o -> o.source() == Source.REVIEW).count();
        long answers = counted.size() - reviews;
        long repositories = counted.stream().map(Observation::repositoryId).distinct().count();
        List<String> parts = new ArrayList<>();
        if (reviews > 0) {
            parts.add(reviews + (reviews == 1 ? " review" : " reviews"));
        }
        if (answers > 0) {
            parts.add(answers + (answers == 1 ? " Scenario Lab answer" : " Scenario Lab answers"));
        }
        return String.join(" and ", parts) + (repositories > 1 ? " across " + repositories + " repositories" : "");
    }

    private static String lowConfidenceNote(long n) {
        return n == 0 ? "" : " " + n + (n == 1 ? " low-confidence assessment is" : " low-confidence assessments are") + " shown but not counted.";
    }

    private static int lowerMedian(List<Observation> observations) {
        List<Integer> ranks = observations.stream().map(o -> rank(o.level())).sorted().toList();
        return ranks.get((ranks.size() - 1) / 2);
    }

    /** Orders the existing assessment scale for comparison only: never averaged, summed or shown. */
    static int rank(Assessment level) {
        return switch (level) {
            case NEEDS_ATTENTION -> 0;
            case DEVELOPING -> 1;
            case SOLID -> 2;
            case STRONG -> 3;
            case NOT_ASSESSABLE -> throw new IllegalArgumentException("NOT_ASSESSABLE is never an observation");
        };
    }

    static String label(int rank) {
        return switch (rank) {
            case 0 -> "Needs attention";
            case 1 -> "Developing";
            case 2 -> "Solid";
            default -> "Strong";
        };
    }
}
