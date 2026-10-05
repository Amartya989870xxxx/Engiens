package com.engineeringlens.analysis.review;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.engineeringlens.analysis.AnalysisRun;
import com.engineeringlens.analysis.AnalysisRunRepository;
import com.engineeringlens.analysis.ai.AiUnavailableException;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.repository.ImportedRepo;
import com.engineeringlens.repository.ImportedRepoRepository;

import tools.jackson.databind.ObjectMapper;

/** Executes one review run off the request thread and records exactly how it ended. */
@Component
class ReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorker.class);

    /** What the user sees for each failure. Provider details stay in logs and model health. */
    static final Map<String, String> USER_MESSAGES = Map.of(
            AiUnavailableException.NOT_CONFIGURED, "AI review isn't set up on this server yet.",
            AiUnavailableException.UNAVAILABLE, "No AI model is available right now. Engiens runs on free AI quotas, which may be used up for today; everything you have already done is still here. Please try again later.",
            AiUnavailableException.REQUEST_REJECTED, "The review request couldn't be processed. Please try again later.",
            "REVIEW_FAILED", "The review failed unexpectedly. Please try again.",
            "INTERRUPTED", "The review was interrupted by a server restart. Please start it again.");

    private final ReviewRunRepository runs;
    private final StoredReviewRepository reviews;
    private final ImportedRepoRepository repositories;
    private final AnalysisRunRepository analysisRuns;
    private final ReviewOrchestrator orchestrator;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    ReviewWorker(ReviewRunRepository runs, StoredReviewRepository reviews, ImportedRepoRepository repositories,
            AnalysisRunRepository analysisRuns, ReviewOrchestrator orchestrator, ObjectMapper json, PlatformTransactionManager tm) {
        this.runs = runs;
        this.reviews = reviews;
        this.repositories = repositories;
        this.analysisRuns = analysisRuns;
        this.orchestrator = orchestrator;
        this.json = json;
        this.transaction = new TransactionTemplate(tm);
    }

    void execute(UUID reviewRunId) {
        ReviewRun run = runs.findById(reviewRunId).orElseThrow();
        run.start();
        runs.save(run);
        log.info("Review started: reviewRun={} repository={}", run.getId(), run.getRepositoryId());
        try {
            ImportedRepo repo = repositories.findById(run.getRepositoryId()).orElseThrow();
            AnalysisRun analysis = analysisRuns.findById(run.getAnalysisRunId()).orElseThrow();
            ReviewOrchestrator.Outcome outcome = orchestrator.review(run.getUserId(), repo, analysis, run.getId());
            // The run records the model that made the assessment; attempts and tokens cover both AI steps.
            var routing = outcome.routing();
            String reviewJson = json.writeValueAsString(outcome.document());
            transaction.executeWithoutResult(tx -> {
                reviews.save(new StoredReview(run.getId(), reviewJson));
                run.complete(routing.provider(), routing.model(), routing.fallbackUsed(), routing.fallbackReason(), outcome.attempts(),
                        outcome.inputTokens(), outcome.outputTokens());
                runs.save(run);
            });
            log.info("Review completed: reviewRun={} provider={} model={} fallback={} reason={} personalised={} attempts={} "
                    + "durationMs={} inputTokens={} outputTokens={}", run.getId(), routing.provider(), routing.model(),
                    routing.fallbackUsed(), routing.fallbackReason(), outcome.teaching() != null, outcome.attempts(),
                    run.getDurationMs(), outcome.inputTokens(), outcome.outputTokens());
        } catch (AiUnavailableException e) {
            fail(run, e.code(), USER_MESSAGES.get(e.code()));
            log.warn("Review failed: reviewRun={} code={} detail={}", run.getId(), e.code(), e.getMessage());
        } catch (ApiException e) {
            fail(run, e.getCode(), e.getMessage()); // e.g. GitHub rate limit while re-reading files: already user-safe
            log.warn("Review failed: reviewRun={} code={}", run.getId(), e.getCode());
        } catch (RuntimeException e) {
            fail(run, "REVIEW_FAILED", USER_MESSAGES.get("REVIEW_FAILED"));
            log.error("Review failed unexpectedly: reviewRun={}", run.getId(), e);
        }
    }

    private void fail(ReviewRun run, String code, String message) {
        run.fail(code, message);
        runs.save(run);
    }
}
