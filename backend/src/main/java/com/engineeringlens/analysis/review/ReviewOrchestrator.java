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
import com.engineeringlens.analysis.review.model.PersonalizedTeaching;
import com.engineeringlens.analysis.review.model.ReviewDocument;
import com.engineeringlens.analysis.review.model.RubricDimension;
import com.engineeringlens.repository.ImportedRepo;

/**
 * Phase 4D in one place: load the prepared context → assess (without the developer profile) → teach
 * (profile + finished review, no code) → stamp Engiens metadata. It does not persist anything
 * (ReviewService does). Splitting assessment from teaching guarantees the same code gets the same
 * verdicts for every developer.
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

    /** Personalised advice is a small answer; it doesn't need the full output budget or deep reasoning. */
    private static final int TEACHING_MAX_OUTPUT_TOKENS = 8192;

    static final String TEACHING_UNAVAILABLE = "Personalised advice couldn't be generated this time, so advice is general. "
            + "The assessments are unaffected.";

    /**
     * The finished review plus how it was produced. {@code teaching} is null when the personalisation step
     * failed and the review kept its general advice.
     */
    public record Outcome(ReviewDocument document, AiModelRouter.Routed<ReviewValidator.Validated> routing,
            AiModelRouter.Routed<PersonalizedTeaching> teaching) {

        public int attempts() {
            return routing.attempts() + (teaching == null ? 0 : teaching.attempts());
        }

        public Integer inputTokens() {
            return sum(routing.inputTokens(), teaching == null ? null : teaching.inputTokens());
        }

        public Integer outputTokens() {
            return sum(routing.outputTokens(), teaching == null ? null : teaching.outputTokens());
        }

        private static Integer sum(Integer a, Integer b) {
            return a == null ? b : b == null ? a : a + b;
        }
    }

    public Outcome review(UUID userId, ImportedRepo repo, AnalysisRun run, UUID reviewRunId) {
        LoadedContext loaded = loader.load(userId, repo, run);
        AiPrompt prompt = prompts.assessment(loaded);
        ReviewValidator.EvidenceIndex index = ReviewValidator.EvidenceIndex.of(loaded);
        log.info("Review prompt built: reviewRun={} files={} signals={} chars={}", reviewRunId,
                loaded.context().contents().size(), loaded.context().analysis().signals().size(),
                prompt.system().length() + prompt.user().length());

        AiGenerationSettings settings = new AiGenerationSettings(ai.generation().temperature(),
                ai.generation().maxOutputTokens(), ai.generation().thinking(), true);
        AiModelRouter.Routed<ReviewValidator.Validated> routed = router.generate(prompt, settings,
                raw -> validator.validate(raw, index), problem -> prompts.repair(prompt, problem));
        if (routed.value().evidenceDropped() + routed.value().linesRemoved() > 0) {
            log.info("Review evidence sanitised: reviewRun={} dropped={} lineRangesRemoved={}", reviewRunId,
                    routed.value().evidenceDropped(), routed.value().linesRemoved());
        }

        ReviewDocument.Personalization personalization = personalizer.personalize(loaded.developer());
        ReviewDocument neutral = routed.value().document();
        AiModelRouter.Routed<PersonalizedTeaching> teaching = teach(neutral, loaded, personalization, reviewRunId);
        ReviewDocument document = teaching == null ? neutral.withLimitation(TEACHING_UNAVAILABLE) : neutral.withTeaching(teaching.value());

        ReviewDocument.ReviewMetadata metadata = new ReviewDocument.ReviewMetadata(repo.getId().toString(), run.getId().toString(),
                reviewRunId.toString(), loaded.context().manifest().commitSha(), routed.provider(), routed.model(),
                routed.fallbackUsed(), RubricDimension.RUBRIC_VERSION, loaded.context().contextSchemaVersion(),
                clock.instant().toString());
        return new Outcome(document.withEngiensFields(metadata, teaching == null ? null : personalization), routed, teaching);
    }

    /** Best effort: a review without tailored advice is still worth showing, so a failure here doesn't fail the run. */
    private AiModelRouter.Routed<PersonalizedTeaching> teach(ReviewDocument neutral, LoadedContext loaded,
            ReviewDocument.Personalization personalization, UUID reviewRunId) {
        AiPrompt prompt = prompts.teaching(neutral, loaded, personalization, personalizer);
        AiGenerationSettings settings = new AiGenerationSettings(ai.generation().temperature(), TEACHING_MAX_OUTPUT_TOKENS, "low", true);
        try {
            AiModelRouter.Routed<PersonalizedTeaching> routed = router.generate(prompt, settings, validator::validateTeaching,
                    problem -> prompts.repair(prompt, problem));
            log.info("Review personalised: reviewRun={} audience={} model={} attempts={}", reviewRunId, personalization.audience(),
                    routed.model(), routed.attempts());
            return routed;
        } catch (RuntimeException e) {
            log.warn("Review personalisation unavailable: reviewRun={} audience={} reason={}", reviewRunId,
                    personalization.audience(), e.getClass().getSimpleName());
            return null;
        }
    }
}
