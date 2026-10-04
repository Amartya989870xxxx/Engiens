package com.engineeringlens.scenario.lab;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.engineeringlens.analysis.AnalysisPreparationService;
import com.engineeringlens.analysis.AnalysisRunResponse;
import com.engineeringlens.analysis.review.ReviewRun;
import com.engineeringlens.analysis.review.ReviewRunRepository;
import com.engineeringlens.analysis.review.ReviewRunStatus;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.repository.RepositoryImportService;
import com.engineeringlens.scenario.Scenario;
import com.engineeringlens.scenario.ScenarioAttempt;
import com.engineeringlens.scenario.ScenarioAttemptRepository;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLabRepository;
import com.engineeringlens.scenario.ScenarioLabStatus;
import com.engineeringlens.scenario.ScenarioRepository;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.generation.ScenarioGenerationWorker;

/**
 * The lab lifecycle: create (from a review, a repository or a GitHub link), read, find the open lab, cancel.
 * Every lookup is scoped to the signed-in user; someone else's lab, repository or review is "not found".
 */
@Service
public class ScenarioLabService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLabService.class);

    public static final Set<Integer> ALLOWED_COUNTS = Set.of(5, 10, 20);

    private final ScenarioLabRepository labs;
    private final ScenarioRepository scenarios;
    private final ScenarioAttemptRepository attempts;
    private final ImportedRepoRepository repositories;
    private final ReviewRunRepository reviewRuns;
    private final RepositoryImportService importer;
    private final AnalysisPreparationService preparation;
    private final ScenarioGenerationWorker generation;
    private final Executor executor;

    public ScenarioLabService(ScenarioLabRepository labs, ScenarioRepository scenarios, ScenarioAttemptRepository attempts,
            ImportedRepoRepository repositories, ReviewRunRepository reviewRuns, RepositoryImportService importer,
            AnalysisPreparationService preparation, ScenarioGenerationWorker generation, @Qualifier("scenarioExecutor") Executor executor) {
        this.labs = labs;
        this.scenarios = scenarios;
        this.attempts = attempts;
        this.repositories = repositories;
        this.reviewRuns = reviewRuns;
        this.importer = importer;
        this.preparation = preparation;
        this.generation = generation;
        this.executor = executor;
    }

    /** Where a lab's snapshot comes from: the repository, the prepared analysis, its commit and the review (if any). */
    record Source(ImportedRepo repository, UUID reviewId, UUID analysisRunId, String commitSha) {
    }

    public ScenarioLabResponse create(UUID userId, CreateScenarioLabRequest request) {
        List<ScenarioRole> roles = validRoles(request.roles());
        if (!ALLOWED_COUNTS.contains(request.scenarioCount())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCENARIO_COUNT", "Choose 5, 10 or 20 scenarios.");
        }
        long sources = Stream.of(request.repositoryUrl(), request.repositoryId(), request.reviewId())
                .filter(s -> s != null && !(s instanceof String str && str.isBlank())).count();
        if (sources != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LAB_SOURCE",
                    "Start a lab from one review, one repository or one GitHub link.");
        }
        // Fail fast, before any slow import or preparation. The unique constraint below is the real guarantee.
        if (labs.findByActiveUserId(userId).isPresent()) {
            throw alreadyActive();
        }

        Source source = request.reviewId() != null ? fromReview(userId, request.reviewId())
                : fromRepository(userId, request.repositoryId() != null ? request.repositoryId()
                        : importer.importRepository(userId, request.repositoryUrl()).id());

        ScenarioLab lab;
        try {
            lab = labs.saveAndFlush(new ScenarioLab(userId, source.repository().getId(), source.reviewId(), source.analysisRunId(),
                    source.commitSha(), roles, request.seniority(), request.scenarioCount()));
        } catch (DataIntegrityViolationException e) {
            throw alreadyActive(); // another tab started a lab at the same moment
        }
        log.info("Scenario lab created: lab={} repository={} review={} commit={} roles={} seniority={} count={}", lab.getId(),
                lab.getRepositoryId(), lab.getReviewId(), lab.getCommitSha(), lab.getRoles(), lab.getSeniority(), lab.getScenarioCount());
        UUID labId = lab.getId();
        try {
            executor.execute(() -> generation.execute(labId));
        } catch (TaskRejectedException e) {
            lab.fail("BUSY", "Engiens is generating many labs right now. Please try again in a few minutes.");
            labs.save(lab);
        }
        return toResponse(labs.findById(labId).orElse(lab), source.repository());
    }

    public ScenarioLabResponse get(UUID userId, UUID labId) {
        ScenarioLab lab = owned(userId, labId);
        return toResponse(lab, repositories.findById(lab.getRepositoryId()).orElse(null));
    }

    /** The user's open lab (generating, active or finalizing), if any. Completed labs are never returned here. */
    public Optional<ScenarioLabResponse> active(UUID userId) {
        return labs.findByActiveUserId(userId).map(lab -> toResponse(lab, repositories.findById(lab.getRepositoryId()).orElse(null)));
    }

    public ScenarioLabResponse cancel(UUID userId, UUID labId) {
        ScenarioLab lab = owned(userId, labId);
        if (lab.getStatus() != ScenarioLabStatus.GENERATING && lab.getStatus() != ScenarioLabStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_LAB_NOT_ACTIVE", "This lab is no longer open.");
        }
        lab.cancel();
        lab = labs.save(lab);
        log.info("Scenario lab cancelled: lab={}", lab.getId());
        return toResponse(lab, repositories.findById(lab.getRepositoryId()).orElse(null));
    }

    public ScenarioLab owned(UUID userId, UUID labId) {
        return labs.findByIdAndUserId(labId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCENARIO_LAB_NOT_FOUND", "We couldn't find that Scenario Lab."));
    }

    /** At least one role, no duplicates, and "Broad Engineering" only on its own. */
    static List<ScenarioRole> validRoles(List<ScenarioRole> requested) {
        List<ScenarioRole> roles = List.copyOf(new LinkedHashSet<>(requested));
        if (roles.contains(ScenarioRole.BROAD_ENGINEERING) && roles.size() > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLES",
                    "Choose Broad Engineering on its own, or pick specific roles.");
        }
        return roles;
    }

    /** The review's own snapshot: the same analysis run and commit the review assessed. */
    private Source fromReview(UUID userId, UUID reviewId) {
        ReviewRun review = reviewRuns.findByIdAndUserId(reviewId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "We couldn't find that review."));
        if (review.getStatus() != ReviewRunStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_COMPLETED", "Scenario Lab can start from a finished review only.");
        }
        if (review.getCommitSha() == null) {
            throw snapshotUnavailable();
        }
        return new Source(ownedRepository(userId, review.getRepositoryId()), review.getId(), review.getAnalysisRunId(),
                review.getCommitSha());
    }

    /** No review: prepare the repository with the existing pipeline (reused when an identical preparation exists). */
    private Source fromRepository(UUID userId, UUID repositoryId) {
        ImportedRepo repo = ownedRepository(userId, repositoryId);
        AnalysisRunResponse prepared = preparation.prepare(userId, repo.getId());
        if (prepared.commitSha() == null) {
            throw snapshotUnavailable();
        }
        return new Source(repo, null, prepared.id(), prepared.commitSha());
    }

    private ImportedRepo ownedRepository(UUID userId, UUID repositoryId) {
        return repositories.findByIdAndUserId(repositoryId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository."));
    }

    ScenarioLabResponse toResponse(ScenarioLab lab, ImportedRepo repo) {
        List<ScenarioLabResponse.ScenarioSummary> summaries = List.of();
        if (lab.getStatus() != ScenarioLabStatus.GENERATING) {
            Map<UUID, ScenarioAttempt> submitted = attempts.findByLabIdOrderByCreatedAtAsc(lab.getId()).stream()
                    .collect(Collectors.toMap(ScenarioAttempt::getScenarioId, Function.identity()));
            summaries = scenarios.findByLabIdOrderByPositionAsc(lab.getId()).stream().map(s -> summary(s, submitted.get(s.getId()))).toList();
        }
        return new ScenarioLabResponse(lab.getId(), lab.getRepositoryId(), repo == null ? null : repo.getGithubRepoName(),
                repo == null ? null : repo.getGithubUrl(), lab.getReviewId(), lab.getCommitSha(), lab.getRoles(), lab.getSeniority(),
                lab.getScenarioCount(), lab.getScenariosReady(), lab.getStatus(), lab.getErrorCode(), lab.getErrorMessage(),
                lab.getCreatedAt(), lab.getCompletedAt(), summaries);
    }

    private static ScenarioLabResponse.ScenarioSummary summary(Scenario s, ScenarioAttempt attempt) {
        return new ScenarioLabResponse.ScenarioSummary(s.getId(), s.getPosition(), s.getTitle(), s.getRole(), s.getCategory(),
                s.getDifficulty(), s.getExecutionCapability(), s.getLanguage(), attempt != null,
                attempt == null ? null : attempt.getEvaluationStatus());
    }

    private static ApiException alreadyActive() {
        return new ApiException(HttpStatus.CONFLICT, "SCENARIO_LAB_ALREADY_ACTIVE",
                "You already have a Scenario Lab in progress. Finish or end it before starting another.");
    }

    private static ApiException snapshotUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_UNAVAILABLE",
                "This repository has no pinned commit. Import it again before starting a lab.");
    }
}
