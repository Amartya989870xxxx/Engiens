package com.engineeringlens.analysis.review;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.engineeringlens.analysis.AnalysisArtifactRepository;
import com.engineeringlens.analysis.AnalysisPreparationService;
import com.engineeringlens.analysis.AnalysisRunResponse;
import com.engineeringlens.analysis.context.ContextBuilder;
import com.engineeringlens.analysis.context.ContextFile;
import com.engineeringlens.analysis.context.ContextManifest;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.analysis.source.RepositorySourceReader;
import com.engineeringlens.analysis.source.RunFileCache;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.ImportedRepoRepository;
import com.engineeringlens.repository.RepositoryStatus;
import com.engineeringlens.repository.RepositoryVisibility;

import tools.jackson.databind.ObjectMapper;

/**
 * Starting, reading and listing reviews. Starting is cheap: it makes sure the repository is prepared
 * (reusing an identical preparation), reuses an identical finished review unless asked to regenerate,
 * and otherwise queues the review for the background worker.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final int MAX_EXCERPT_LINES = 80;

    private final ImportedRepoRepository repositories;
    private final AnalysisPreparationService preparation;
    private final ReviewRunRepository runs;
    private final StoredReviewRepository reviews;
    private final AnalysisArtifactRepository artifacts;
    private final RepositorySourceReader sources;
    private final ReviewWorker worker;
    private final Executor executor;
    private final ObjectMapper json;

    public ReviewService(ImportedRepoRepository repositories, AnalysisPreparationService preparation, ReviewRunRepository runs,
            StoredReviewRepository reviews, AnalysisArtifactRepository artifacts, RepositorySourceReader sources, ReviewWorker worker,
            @Qualifier("reviewExecutor") Executor executor, ObjectMapper json) {
        this.repositories = repositories;
        this.preparation = preparation;
        this.runs = runs;
        this.reviews = reviews;
        this.artifacts = artifacts;
        this.sources = sources;
        this.worker = worker;
        this.executor = executor;
        this.json = json;
    }

    public ReviewRunResponse start(UUID userId, UUID repositoryId, boolean regenerate) {
        ImportedRepo repo = owned(userId, repositoryId);
        if (repo.getStatus() != RepositoryStatus.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_NOT_READY", "Import the repository successfully before reviewing it.");
        }
        AnalysisRunResponse prepared = preparation.prepare(userId, repositoryId); // reused when identical
        if (!regenerate) {
            Optional<ReviewRun> done = runs.findFirstByAnalysisRunIdAndStatusAndRubricVersionAndReviewSchemaVersionOrderByCreatedAtDesc(
                    prepared.id(), ReviewRunStatus.COMPLETED, RubricDimension.RUBRIC_VERSION, ReviewDocument.SCHEMA_VERSION);
            if (done.isPresent()) {
                return toResponse(done.get(), repo, false); // same commit, context and rubric: don't spend quota again
            }
        }
        Optional<ReviewRun> inProgress = runs.findFirstByAnalysisRunIdAndStatusInOrderByCreatedAtDesc(prepared.id(),
                EnumSet.of(ReviewRunStatus.QUEUED, ReviewRunStatus.RUNNING));
        if (inProgress.isPresent()) {
            return toResponse(inProgress.get(), repo, false);
        }
        ReviewRun run = runs.save(new ReviewRun(repo.getId(), prepared.id(), userId, prepared.commitSha(), ReviewDocument.SCHEMA_VERSION,
                RubricDimension.RUBRIC_VERSION, prepared.contextSchemaVersion()));
        try {
            executor.execute(() -> worker.execute(run.getId()));
        } catch (TaskRejectedException e) {
            run.fail("BUSY", "Engiens is reviewing many repositories right now. Please try again in a few minutes.");
            runs.save(run);
        }
        return toResponse(runs.findById(run.getId()).orElse(run), repo, false);
    }

    public ReviewRunResponse get(UUID userId, UUID reviewRunId) {
        ReviewRun run = runs.findByIdAndUserId(reviewRunId, userId).orElseThrow(ReviewService::notFound);
        return toResponse(run, repositories.findById(run.getRepositoryId()).orElse(null), true);
    }

    public ReviewRunResponse latest(UUID userId, UUID repositoryId) {
        ImportedRepo repo = owned(userId, repositoryId);
        return runs.findFirstByRepositoryIdAndUserIdAndStatusOrderByCreatedAtDesc(repo.getId(), userId, ReviewRunStatus.COMPLETED)
                .map(r -> toResponse(r, repo, true))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "This repository hasn't been reviewed yet."));
    }

    public List<ReviewRunResponse> history(UUID userId, UUID repositoryId) {
        ImportedRepo repo = owned(userId, repositoryId);
        return runs.findByRepositoryIdAndUserIdOrderByCreatedAtDesc(repo.getId(), userId).stream().map(r -> toResponse(r, repo, false)).toList();
    }

    /** The user's most recent completed reviews across repositories (for the sidebar). */
    public List<ReviewRunResponse> recent(UUID userId) {
        return runs.findTop10ByUserIdAndStatusOrderByCreatedAtDesc(userId, ReviewRunStatus.COMPLETED).stream()
                .map(r -> toResponse(r, repositories.findById(r.getRepositoryId()).orElse(null), false)).toList();
    }

    /**
     * Lines of a file the review cites, read now from the pinned commit. Only files whose content was part
     * of the review's context can be read, and only within what the reviewer saw; the text is verified
     * against the stored hash first. Nothing is stored.
     */
    public ExcerptResponse excerpt(UUID userId, UUID reviewRunId, String file, int lineStart, int lineEnd) {
        ReviewRun run = runs.findByIdAndUserId(reviewRunId, userId).orElseThrow(ReviewService::notFound);
        ImportedRepo repo = repositories.findById(run.getRepositoryId()).orElseThrow(ReviewService::notFound);
        ContextManifest manifest = json.readValue(artifacts.findById(run.getAnalysisRunId()).orElseThrow(ReviewService::notFound)
                .getContextManifestJson(), ContextManifest.class);
        ContextFile meta = manifest.files().stream().filter(f -> f.path().equals(file) && f.sha256() != null).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXCERPT_NOT_AVAILABLE",
                        "This file's content wasn't part of the review."));
        RunFileCache.Fetched fetched = new RunFileCache(sources.open(userId, repo.getGithubOwner(), repo.getGithubRepoName(),
                repo.getDefaultBranch(), repo.getVisibility() == RepositoryVisibility.PRIVATE, manifest.commitSha())).fetch(file);
        String text = fetched.ok() ? ContextBuilder.firstBytes(fetched.text(), (int) meta.includedBytes()) : null;
        if (text == null || !ContextBuilder.sha256(text).equals(meta.sha256())) {
            throw new ApiException(HttpStatus.CONFLICT, "EXCERPT_NOT_AVAILABLE", "This file can't be shown: it's no longer available as reviewed.");
        }
        List<String> lines = text.lines().toList();
        int start = Math.max(1, lineStart);
        int end = Math.min(Math.min(lineEnd < start ? start : lineEnd, lines.size()), start + MAX_EXCERPT_LINES - 1);
        if (start > lines.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXCERPT_OUT_OF_RANGE", "Those lines aren't part of the reviewed content.");
        }
        List<ExcerptResponse.Line> excerpt = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) {
            excerpt.add(new ExcerptResponse.Line(i, lines.get(i - 1)));
        }
        return new ExcerptResponse(file, start, end, excerpt);
    }

    /** Reviews still marked as running when the server stopped will never finish: say so. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedReviews() {
        List<ReviewRun> stuck = runs.findByStatusIn(EnumSet.of(ReviewRunStatus.QUEUED, ReviewRunStatus.RUNNING));
        for (ReviewRun run : stuck) {
            run.fail("INTERRUPTED", ReviewWorker.USER_MESSAGES.get("INTERRUPTED"));
            runs.save(run);
        }
        if (!stuck.isEmpty()) {
            log.warn("Marked {} interrupted review(s) as failed after restart", stuck.size());
        }
    }

    private ImportedRepo owned(UUID userId, UUID repositoryId) {
        return repositories.findByIdAndUserId(repositoryId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository."));
    }

    private ReviewRunResponse toResponse(ReviewRun run, ImportedRepo repo, boolean withReview) {
        ReviewDocument review = null;
        if (withReview && run.getStatus() == ReviewRunStatus.COMPLETED) {
            review = reviews.findById(run.getId()).map(r -> json.readValue(r.getReviewJson(), ReviewDocument.class)).orElse(null);
        }
        return new ReviewRunResponse(run.getId(), run.getRepositoryId(), repo == null ? null : repo.getGithubRepoName(),
                run.getAnalysisRunId(), run.getStatus(), run.getCommitSha(), run.getProvider(), run.getModel(), run.isFallbackUsed(),
                run.getErrorCode(), run.getErrorMessage(), run.getCreatedAt(), run.getCompletedAt(), run.getDurationMs(), review);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "We couldn't find that review.");
    }
}
