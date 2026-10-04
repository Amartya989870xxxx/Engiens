package com.engineeringlens.analysis.review;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.AnalysisRun;
import com.engineeringlens.analysis.ai.AiGenerationSettings;
import com.engineeringlens.analysis.ai.AiModelRouter;
import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.ai.AiProperties;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.repository.ImportedRepo;

/**
 * Phase 4D in one place: load the prepared context → personalise → build the prompt → let the router
 * get a validated answer → stamp Engiens metadata. It does not persist anything (ReviewService does).
 */
@Component
public class ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final AnalysisContextLoader loader;
    private final ReviewPersonalizer personalizer;
    private final ReviewPromptBuilder prompts;
    private final ReviewValidator validator;
    private final AiModelRouter router;
    private final AiProperties ai;
    private final Clock clock;

    public ReviewOrchestrator(AnalysisContextLoader loader, ReviewPersonalizer personalizer, ReviewPromptBuilder prompts,
            ReviewValidator validator, AiModelRouter router, AiProperties ai, Clock clock) {
        this.loader = loader;
        this.personalizer = personalizer;
        this.prompts = prompts;
        this.validator = validator;
        this.router = router;
        this.ai = ai;
        this.clock = clock;
    }

    /** The finished review plus how it was produced. */
    public record Outcome(ReviewDocument document, AiModelRouter.Routed<ReviewValidator.Validated> routing) {
    }

    public Outcome review(UUID userId, ImportedRepo repo, AnalysisRun run, UUID reviewRunId) {
        LoadedContext loaded = loader.load(userId, repo, run);
        ReviewDocument.Personalization personalization = personalizer.personalize(loaded.developer());
        AiPrompt prompt = prompts.build(loaded, personalization, personalizer);
        ReviewValidator.EvidenceIndex index = ReviewValidator.EvidenceIndex.of(loaded);
        log.info("Review prompt built: reviewRun={} files={} signals={} chars={} audience={}", reviewRunId,
                loaded.context().contents().size(), loaded.context().analysis().signals().size(),
                prompt.system().length() + prompt.user().length(), personalization.audience());

        AiGenerationSettings settings = new AiGenerationSettings(ai.generation().temperature(),
                ai.generation().maxOutputTokens(), ai.generation().thinking(), true);
        AiModelRouter.Routed<ReviewValidator.Validated> routed = router.generate(prompt, settings,
                raw -> validator.validate(raw, index), problem -> prompts.repair(prompt, problem));
        if (routed.value().evidenceDropped() + routed.value().linesRemoved() > 0) {
            log.info("Review evidence sanitised: reviewRun={} dropped={} lineRangesRemoved={}", reviewRunId,
                    routed.value().evidenceDropped(), routed.value().linesRemoved());
        }

        ReviewDocument.ReviewMetadata metadata = new ReviewDocument.ReviewMetadata(repo.getId().toString(), run.getId().toString(),
                reviewRunId.toString(), loaded.context().manifest().commitSha(), routed.provider(), routed.model(),
                routed.fallbackUsed(), RubricDimension.RUBRIC_VERSION, loaded.context().contextSchemaVersion(),
                clock.instant().toString());
        return new Outcome(routed.value().document().withEngiensFields(metadata, personalization), routed);
    }
}
