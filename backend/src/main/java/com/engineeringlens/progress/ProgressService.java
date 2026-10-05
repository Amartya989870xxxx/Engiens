package com.engineeringlens.progress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringlens.analysis.review.ReviewRun;
import com.engineeringlens.analysis.review.ReviewRunRepository;
import com.engineeringlens.analysis.review.ReviewRunStatus;
import com.engineeringlens.analysis.review.StoredReview;
import com.engineeringlens.analysis.review.StoredReviewRepository;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.ReviewEnums.Applicability;
import com.engineeringlens.analysis.review.model.ReviewEnums.Assessment;
import com.engineeringlens.analysis.review.model.ReviewEnums.Severity;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.progress.ProgressCalculator.AreaProgress;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.scenario.EvaluationStatus;
import com.engineeringlens.scenario.Scenario;
import com.engineeringlens.scenario.ScenarioAttempt;
import com.engineeringlens.scenario.ScenarioAttemptRepository;
import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLabAssessmentRepository;
import com.engineeringlens.scenario.ScenarioLabRepository;
import com.engineeringlens.scenario.ScenarioLabStatus;
import com.engineeringlens.scenario.ScenarioRepository;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;
import com.engineeringlens.scenario.model.LabAssessment;
import com.engineeringlens.scenario.model.ScenarioEvaluation;
import com.engineeringlens.scenario.model.ScenarioReference;

import tools.jackson.databind.ObjectMapper;

/**
 * Reads the user's stored reviews and completed labs and turns them into the Progress page. Nothing is written and
 * no model is called: the persisted assessments are the only source of truth, and the calculation is deterministic.
 *
 * <p>Evidence is bounded (the newest 50 reviews and 50 labs); the page says when that limit applied. Stored JSON is
 * treated as untrusted: an assessment that can't be read or has an older format is skipped and counted, never fatal.
 */
@Service
public class ProgressService {

    private static final Logger log = LoggerFactory.getLogger(ProgressService.class);
    static final int MAX = 50;
    static final int HISTORY_PAGE = 20;

    private final ReviewRunRepository reviewRuns;
    private final StoredReviewRepository reviews;
    private final ScenarioLabRepository labs;
    private final ScenarioAttemptRepository attempts;
    private final ScenarioRepository scenarios;
    private final ScenarioLabAssessmentRepository assessments;
    private final ImportedRepoRepository repositories;
    private final ObjectMapper json;
    private final ProgressCalculator calculator = new ProgressCalculator();

    public ProgressService(ReviewRunRepository reviewRuns, StoredReviewRepository reviews, ScenarioLabRepository labs,
            ScenarioAttemptRepository attempts, ScenarioRepository scenarios, ScenarioLabAssessmentRepository assessments,
            ImportedRepoRepository repositories, ObjectMapper json) {
        this.reviewRuns = reviewRuns;
        this.reviews = reviews;
        this.labs = labs;
        this.attempts = attempts;
        this.scenarios = scenarios;
        this.assessments = assessments;
        this.repositories = repositories;
        this.json = json;
    }

    /** Everything Progress reads, loaded once per request. */
    private record Evidence(List<Observation> observations, List<LoadedReview> reviews, List<LoadedLab> labs, Map<UUID, String> names,
            int skipped, boolean truncated) {
    }

    private record LoadedReview(ReviewRun run, ReviewDocument document) {
    }

    /** A completed lab with its evaluated answers (verdict NOT_ASSESSABLE included: it was still submitted). */
    private record LoadedLab(ScenarioLab lab, List<ScenarioAttempt> attempts, Map<UUID, ScenarioEvaluation> evaluations) {
    }

    @Transactional(readOnly = true)
    public ProgressResponse overview(UUID userId, UUID repositoryId) {
        String scopeName = repositoryId == null ? null : ownedName(userId, repositoryId);
        Evidence evidence = load(userId, repositoryId);
        List<AreaProgress> areas = calculator.areas(evidence.observations());

        Set<UUID> evidenceRepositories = new HashSet<>();
        evidence.reviews().forEach(r -> evidenceRepositories.add(r.run().getRepositoryId()));
        evidence.labs().forEach(l -> evidenceRepositories.add(l.lab().getRepositoryId()));
        List<Instant> dates = Stream.concat(evidence.reviews().stream().map(r -> r.run().getCompletedAt()),
                evidence.labs().stream().map(l -> l.lab().getCompletedAt())).filter(Objects::nonNull).sorted().toList();
        int answers = evidence.labs().stream().mapToInt(l -> l.evaluations().size()).sum();
        ProgressResponse.EvidenceSummary summary = new ProgressResponse.EvidenceSummary(evidence.reviews().size(), evidenceRepositories.size(),
                evidence.labs().size(), answers, dates.isEmpty() ? null : dates.get(0), dates.isEmpty() ? null : dates.get(dates.size() - 1),
                evidence.skipped(), evidence.truncated());

        return new ProgressResponse(new ProgressResponse.Scope(repositoryId, scopeName), repositoryOptions(userId), summary,
                areas.stream().map(ProgressService::area).toList(), practice(evidence, areas),
                calculator.nextAreas(areas).stream()
                        .map(n -> new ProgressResponse.NextArea(n.area(), n.area().displayName(), n.why())).toList(),
                new ProgressResponse.Recommendations(reviewTeaching(evidence), labTeaching(evidence)));
    }

    @Transactional(readOnly = true)
    public ProgressHistoryResponse history(UUID userId, UUID repositoryId, int page) {
        if (repositoryId != null) {
            ownedName(userId, repositoryId);
        }
        Evidence evidence = load(userId, repositoryId);
        List<ProgressHistoryResponse.Item> items = new ArrayList<>();
        for (LoadedReview r : evidence.reviews()) {
            items.add(new ProgressHistoryResponse.Item(ProgressHistoryResponse.Type.REVIEW, r.run().getId(), r.run().getRepositoryId(),
                    evidence.names().get(r.run().getRepositoryId()), r.run().getCommitSha(), r.run().getCompletedAt(),
                    r.document().overallAssessment().level(), null, null, null, null));
        }
        for (LoadedLab l : evidence.labs()) {
            items.add(new ProgressHistoryResponse.Item(ProgressHistoryResponse.Type.LAB, l.lab().getId(), l.lab().getRepositoryId(),
                    evidence.names().get(l.lab().getRepositoryId()), l.lab().getCommitSha(), l.lab().getCompletedAt(), null, l.lab().getRoles(),
                    l.lab().getSeniority(), l.lab().scenariosAvailable(), l.attempts().size()));
        }
        items.sort(Comparator.comparing(ProgressHistoryResponse.Item::date, Comparator.nullsLast(Comparator.reverseOrder())));
        int safePage = Math.max(0, page);
        int from = Math.min(items.size(), safePage * HISTORY_PAGE);
        int to = Math.min(items.size(), from + HISTORY_PAGE);
        return new ProgressHistoryResponse(List.copyOf(items.subList(from, to)), safePage, to < items.size());
    }

    // ---- loading -----------------------------------------------------------------------------------

    private Evidence load(UUID userId, UUID repositoryId) {
        List<ReviewRun> runs = repositoryId == null
                ? reviewRuns.findTop50ByUserIdAndStatusOrderByCompletedAtDesc(userId, ReviewRunStatus.COMPLETED)
                : reviewRuns.findTop50ByUserIdAndRepositoryIdAndStatusOrderByCompletedAtDesc(userId, repositoryId, ReviewRunStatus.COMPLETED);
        List<ScenarioLab> completedLabs = repositoryId == null
                ? labs.findTop50ByUserIdAndStatusOrderByCompletedAtDesc(userId, ScenarioLabStatus.COMPLETED)
                : labs.findTop50ByUserIdAndRepositoryIdAndStatusOrderByCompletedAtDesc(userId, repositoryId, ScenarioLabStatus.COMPLETED);
        boolean truncated = runs.size() >= MAX || completedLabs.size() >= MAX;

        Set<UUID> repositoryIds = new HashSet<>();
        runs.forEach(r -> repositoryIds.add(r.getRepositoryId()));
        completedLabs.forEach(l -> repositoryIds.add(l.getRepositoryId()));
        Map<UUID, String> names = repositories.findAllById(repositoryIds).stream().filter(r -> r.getUserId().equals(userId))
                .collect(Collectors.toMap(ImportedRepo::getId, ProgressService::name));

        List<Observation> observations = new ArrayList<>();
        int skipped = 0;
        Map<UUID, StoredReview> stored = reviews.findAllById(runs.stream().map(ReviewRun::getId).toList()).stream()
                .collect(Collectors.toMap(StoredReview::getReviewRunId, Function.identity()));
        List<LoadedReview> loadedReviews = new ArrayList<>();
        for (ReviewRun run : runs) {
            ReviewDocument document = readReview(run, stored.get(run.getId()));
            if (document == null) {
                skipped++;
                continue;
            }
            loadedReviews.add(new LoadedReview(run, document));
            for (ReviewDocument.DimensionReview d : document.dimensions()) {
                if (d.applicability() != Applicability.APPLICABLE || d.assessment() == Assessment.NOT_ASSESSABLE) {
                    continue;
                }
                int high = (int) d.concerns().stream().filter(c -> c.severity() == Severity.HIGH).count();
                observations.add(Observation.review(d.id(), d.assessment(), d.confidence(), run.getCompletedAt(), run.getRepositoryId(),
                        names.get(run.getRepositoryId()), run.getCommitSha(), run.getId(), high));
            }
        }

        List<ScenarioAttempt> allAttempts = completedLabs.isEmpty() ? List.of()
                : attempts.findByLabIdInOrderByCreatedAtAsc(completedLabs.stream().map(ScenarioLab::getId).toList());
        Map<UUID, Scenario> scenarioById = scenarios.findAllById(allAttempts.stream().map(ScenarioAttempt::getScenarioId).toList()).stream()
                .collect(Collectors.toMap(Scenario::getId, Function.identity()));
        Map<UUID, List<ScenarioAttempt>> attemptsByLab = allAttempts.stream()
                .filter(a -> a.getUserId().equals(userId))
                .collect(Collectors.groupingBy(ScenarioAttempt::getLabId, LinkedHashMap::new, Collectors.toList()));
        List<LoadedLab> loadedLabs = new ArrayList<>();
        for (ScenarioLab lab : completedLabs) {
            List<ScenarioAttempt> labAttempts = attemptsByLab.getOrDefault(lab.getId(), List.of());
            Map<UUID, ScenarioEvaluation> evaluations = new LinkedHashMap<>();
            for (ScenarioAttempt attempt : labAttempts) {
                ScenarioEvaluation evaluation = readEvaluation(attempt);
                if (evaluation == null) {
                    if (attempt.getEvaluationStatus() == EvaluationStatus.COMPLETED) {
                        skipped++;
                    }
                    continue;
                }
                evaluations.put(attempt.getId(), evaluation);
                if (evaluation.verdict() == Assessment.NOT_ASSESSABLE) {
                    continue;
                }
                Scenario scenario = scenarioById.get(attempt.getScenarioId());
                String title = scenario == null ? null : scenario.getTitle();
                EngineeringAreas.Mapping mapping = EngineeringAreas.areaOf(attempt.getCategory(), title, concepts(attempt, scenario));
                String note = attempt.getCategory() != ScenarioCategory.AI_ML_ENGINEERING ? null
                        : "AI/ML scenario, counted under " + mapping.area().displayName()
                                + (mapping.matched() == null ? "." : " (it is about \"" + mapping.matched() + "\").");
                observations.add(Observation.scenario(mapping.area(), evaluation.verdict(), evaluation.confidence(), attempt.getCreatedAt(),
                        lab.getRepositoryId(), names.get(lab.getRepositoryId()), lab.getCommitSha(), lab.getId(), attempt.getId(),
                        attempt.getCategory(), title, note));
            }
            loadedLabs.add(new LoadedLab(lab, labAttempts, evaluations));
        }
        return new Evidence(observations, loadedReviews, loadedLabs, names, skipped, truncated);
    }

    /** A completed review in the current format, or null (an older or unreadable one isn't comparable). */
    private ReviewDocument readReview(ReviewRun run, StoredReview stored) {
        if (stored == null || run.getRubricVersion() != RubricDimension.RUBRIC_VERSION
                || run.getReviewSchemaVersion() != ReviewDocument.SCHEMA_VERSION) {
            return null;
        }
        try {
            return json.readValue(stored.getReviewJson(), ReviewDocument.class);
        } catch (RuntimeException e) {
            log.warn("Progress skipped an unreadable review: reviewRun={}", run.getId());
            return null;
        }
    }

    private ScenarioEvaluation readEvaluation(ScenarioAttempt attempt) {
        if (attempt.getEvaluationStatus() != EvaluationStatus.COMPLETED || attempt.getEvaluationJson() == null) {
            return null;
        }
        try {
            ScenarioEvaluation evaluation = json.readValue(attempt.getEvaluationJson(), ScenarioEvaluation.class);
            return evaluation.scenarioEvaluationSchemaVersion() == ScenarioEvaluation.SCHEMA_VERSION ? evaluation : null;
        } catch (RuntimeException e) {
            log.warn("Progress skipped an unreadable evaluation: attempt={}", attempt.getId());
            return null;
        }
    }

    /** Expected concepts decide an AI/ML scenario's area. They stay on the server: only the resulting area is returned. */
    private List<String> concepts(ScenarioAttempt attempt, Scenario scenario) {
        if (attempt.getCategory() != ScenarioCategory.AI_ML_ENGINEERING || scenario == null) {
            return List.of();
        }
        try {
            ScenarioReference reference = json.readValue(scenario.getReferenceJson(), ScenarioReference.class);
            return reference.expectedConcepts() == null ? List.of() : reference.expectedConcepts();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ---- shaping -----------------------------------------------------------------------------------

    private static ProgressResponse.Area area(AreaProgress a) {
        return new ProgressResponse.Area(a.area(), a.area().displayName(), a.indicator(), a.reason(),
                a.projectChanges().stream().map(c -> new ProgressResponse.ProjectChange(c.repositoryId(), c.repositoryName(), c.from(), c.to(),
                        c.fromCommit(), c.toCommit(), c.fromDate(), c.toDate(), c.direction())).toList(),
                a.variedOnSameCode(),
                a.evidence().stream().map(e -> {
                    Observation o = e.observation();
                    String note = Stream.of(o.note(), e.note()).filter(Objects::nonNull).collect(Collectors.joining(" "));
                    return new ProgressResponse.EvidenceItem(o.source(), o.level(), o.confidence(), o.at(), o.repositoryId(), o.repositoryName(),
                            o.commitSha(), o.reviewId(), o.labId(), o.attemptId(), o.category(), o.scenarioTitle(), e.counted(),
                            note.isEmpty() ? null : note);
                }).toList());
    }

    private ProgressResponse.Practice practice(Evidence evidence, List<AreaProgress> areas) {
        Map<ScenarioCategory, Map<Assessment, Integer>> verdicts = new EnumMap<>(ScenarioCategory.class);
        Map<ScenarioCategory, RubricDimension> areaOfCategory = new EnumMap<>(ScenarioCategory.class);
        Map<ScenarioRole, Integer> roles = new EnumMap<>(ScenarioRole.class);
        Map<Seniority, Integer> seniorities = new EnumMap<>(Seniority.class);
        Map<UUID, RubricDimension> answerArea = new HashMap<>();
        evidence.observations().stream().filter(o -> o.attemptId() != null).forEach(o -> answerArea.put(o.attemptId(), o.area()));
        for (LoadedLab lab : evidence.labs()) {
            for (ScenarioAttempt attempt : lab.attempts()) {
                ScenarioEvaluation evaluation = lab.evaluations().get(attempt.getId());
                if (evaluation == null) {
                    continue;
                }
                verdicts.computeIfAbsent(attempt.getCategory(), c -> new EnumMap<>(Assessment.class)).merge(evaluation.verdict(), 1, Integer::sum);
                if (answerArea.containsKey(attempt.getId())) {
                    areaOfCategory.putIfAbsent(attempt.getCategory(), answerArea.get(attempt.getId()));
                }
                roles.merge(attempt.getRole(), 1, Integer::sum);
                seniorities.merge(attempt.getSeniority(), 1, Integer::sum);
            }
        }
        List<ProgressResponse.CategoryPractice> categories = verdicts.entrySet().stream()
                .map(e -> new ProgressResponse.CategoryPractice(e.getKey(),
                        areaOfCategory.getOrDefault(e.getKey(), EngineeringAreas.areaOf(e.getKey(), null, List.of()).area()),
                        e.getValue().values().stream().mapToInt(Integer::intValue).sum(), e.getValue()))
                .sorted(Comparator.comparingInt((ProgressResponse.CategoryPractice c) -> -c.answers()).thenComparing(ProgressResponse.CategoryPractice::category))
                .toList();
        return new ProgressResponse.Practice(categories, counts(roles), counts(seniorities),
                calculator.weakButUnpractised(areas).stream().map(AreaProgress::area).toList());
    }

    private static <T extends Comparable<T>> List<ProgressResponse.Count<T>> counts(Map<T, Integer> counts) {
        return counts.entrySet().stream().map(e -> new ProgressResponse.Count<>(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt((ProgressResponse.Count<T> c) -> -c.count()).thenComparing(ProgressResponse.Count::value)).toList();
    }

    /** The latest review's own "next things to learn", quoted. */
    private static ProgressResponse.StoredTeaching reviewTeaching(Evidence evidence) {
        return evidence.reviews().stream().findFirst().map(r -> {
            ReviewDocument.LearningPlan plan = r.document().personalizedLearningPlan();
            List<ProgressResponse.Topic> topics = plan == null ? List.of() : plan.nextThingsToLearn().stream()
                    .sorted(Comparator.comparing(ReviewDocument.LearningTopic::suggestedOrder))
                    .map(t -> new ProgressResponse.Topic(t.topic(), t.why())).limit(5).toList();
            return new ProgressResponse.StoredTeaching(r.run().getId(), null, r.run().getRepositoryId(),
                    evidence.names().get(r.run().getRepositoryId()), r.run().getCompletedAt(), topics);
        }).filter(t -> !t.topics().isEmpty()).orElse(null);
    }

    /** The latest completed lab's stored learning recommendations, quoted. */
    private ProgressResponse.StoredTeaching labTeaching(Evidence evidence) {
        for (LoadedLab lab : evidence.labs()) {
            LabAssessment assessment = assessments.findById(lab.lab().getId()).map(a -> {
                try {
                    return json.readValue(a.getAssessmentJson(), LabAssessment.class);
                } catch (RuntimeException e) {
                    return null;
                }
            }).orElse(null);
            if (assessment == null || assessment.learningRecommendations() == null || assessment.learningRecommendations().isEmpty()) {
                continue;
            }
            return new ProgressResponse.StoredTeaching(null, lab.lab().getId(), lab.lab().getRepositoryId(),
                    evidence.names().get(lab.lab().getRepositoryId()), lab.lab().getCompletedAt(),
                    assessment.learningRecommendations().stream().map(t -> new ProgressResponse.Topic(t.topic(), t.why())).limit(5).toList());
        }
        return null;
    }

    /** Repositories with at least one completed review or lab, for the scope selector. */
    private List<ProgressResponse.RepositoryOption> repositoryOptions(UUID userId) {
        Set<UUID> withEvidence = new HashSet<>();
        reviewRuns.findTop50ByUserIdAndStatusOrderByCompletedAtDesc(userId, ReviewRunStatus.COMPLETED).forEach(r -> withEvidence.add(r.getRepositoryId()));
        labs.findTop50ByUserIdAndStatusOrderByCompletedAtDesc(userId, ScenarioLabStatus.COMPLETED).forEach(l -> withEvidence.add(l.getRepositoryId()));
        return repositories.findByUserIdOrderByUpdatedAtDesc(userId).stream().filter(r -> withEvidence.contains(r.getId()))
                .map(r -> new ProgressResponse.RepositoryOption(r.getId(), name(r))).toList();
    }

    private String ownedName(UUID userId, UUID repositoryId) {
        return repositories.findByIdAndUserId(repositoryId, userId).map(ProgressService::name)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository."));
    }

    private static String name(ImportedRepo repo) {
        return repo.getGithubRepoName();
    }
}
