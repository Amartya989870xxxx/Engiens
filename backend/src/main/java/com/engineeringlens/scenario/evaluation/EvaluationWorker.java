package com.engineeringlens.scenario.evaluation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.engineeringlens.analysis.ai.AiGenerationSettings;
import com.engineeringlens.analysis.ai.AiModelRouter;
import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.ai.AiProperties;
import com.engineeringlens.analysis.ai.AiUnavailableException;
import com.engineeringlens.analysis.context.DeveloperProfile;
import com.engineeringlens.analysis.review.ReviewPersonalizer;
import com.engineeringlens.analysis.review.model.ReviewDocument.Personalization;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.EvaluationStatus;
import com.engineeringlens.scenario.Scenario;
import com.engineeringlens.scenario.ScenarioAttempt;
import com.engineeringlens.scenario.ScenarioAttemptRepository;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLabAssessment;
import com.engineeringlens.scenario.ScenarioLabAssessmentRepository;
import com.engineeringlens.scenario.ScenarioLabRepository;
import com.engineeringlens.scenario.ScenarioLabStatus;
import com.engineeringlens.scenario.ScenarioRepository;
import com.engineeringlens.scenario.evaluation.EvaluationPromptBuilder.EvaluatedScenario;
import com.engineeringlens.scenario.execution.RunResult;
import com.engineeringlens.scenario.model.LabAssessment;
import com.engineeringlens.scenario.model.LabSummary;
import com.engineeringlens.scenario.model.LabTeaching;
import com.engineeringlens.scenario.model.ScenarioDocument;
import com.engineeringlens.scenario.model.ScenarioEvaluation;
import com.engineeringlens.scenario.model.ScenarioReference;
import com.engineeringlens.scenario.model.ScenarioWorkspace;
import com.engineeringlens.scenario.workspace.FileContent;
import com.engineeringlens.user.UserProfileRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Evaluates submissions off the request thread and completes the lab when the last one is evaluated:
 * assess each attempt (no profile) → summarise the lab (no profile) → teach (profile, no code) → save the
 * assessment and close the workspace. Completion runs once even if two evaluations finish together (the lab's
 * optimistic lock lets only one move it to FINALIZING).
 */
@Component
public class EvaluationWorker {

    private static final Logger log = LoggerFactory.getLogger(EvaluationWorker.class);
    private static final int SUMMARY_MAX_OUTPUT_TOKENS = 8192;
    private static final TypeReference<List<FileContent>> FILES = new TypeReference<>() {
    };

    static final Map<String, String> USER_MESSAGES = Map.of(
            AiUnavailableException.NOT_CONFIGURED, "AI evaluation isn't set up on this server yet.",
            AiUnavailableException.UNAVAILABLE, "No AI model is available right now. Try the evaluation again in a few minutes.",
            AiUnavailableException.REQUEST_REJECTED, "The evaluation request couldn't be processed. Please try again later.",
            "EVALUATION_FAILED", "The evaluation failed unexpectedly. Please try again.",
            "INTERRUPTED", "The evaluation was interrupted by a server restart. Please try again.");
    static final String TEACHING_UNAVAILABLE = "Personalised learning points couldn't be generated this time. The assessment is unaffected.";

    private final ScenarioLabRepository labs;
    private final ScenarioRepository scenarios;
    private final ScenarioAttemptRepository attempts;
    private final ScenarioLabAssessmentRepository assessments;
    private final UserProfileRepository profiles;
    private final EvaluationPromptBuilder prompts;
    private final EvaluationValidator validator;
    private final ReviewPersonalizer personalizer;
    private final AiModelRouter router;
    private final AiProperties ai;
    private final Executor executor;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public EvaluationWorker(ScenarioLabRepository labs, ScenarioRepository scenarios, ScenarioAttemptRepository attempts,
            ScenarioLabAssessmentRepository assessments, UserProfileRepository profiles, EvaluationPromptBuilder prompts,
            EvaluationValidator validator, ReviewPersonalizer personalizer, AiModelRouter router, AiProperties ai,
            @Qualifier("scenarioExecutor") Executor executor, ObjectMapper json, PlatformTransactionManager tm) {
        this.labs = labs;
        this.scenarios = scenarios;
        this.attempts = attempts;
        this.assessments = assessments;
        this.profiles = profiles;
        this.prompts = prompts;
        this.validator = validator;
        this.personalizer = personalizer;
        this.router = router;
        this.ai = ai;
        this.executor = executor;
        this.json = json;
        this.transaction = new TransactionTemplate(tm);
    }

    // ---- one submission -------------------------------------------------------------------------

    public void evaluate(UUID attemptId) {
        ScenarioAttempt attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getEvaluationStatus() != EvaluationStatus.PENDING) {
            return;
        }
        try {
            ScenarioLab lab = labs.findById(attempt.getLabId()).orElseThrow();
            Scenario s = scenarios.findById(attempt.getScenarioId()).orElseThrow();
            AiPrompt prompt = prompts.assess(lab, s, json.readValue(s.getScenarioJson(), ScenarioDocument.class),
                    json.readValue(s.getReferenceJson(), ScenarioReference.class),
                    s.getWorkspaceJson() == null ? null : json.readValue(s.getWorkspaceJson(), ScenarioWorkspace.class), attempt,
                    attempt.getSubmittedFilesJson() == null ? null : json.readValue(attempt.getSubmittedFilesJson(), FILES),
                    attempt.getRunResultJson() == null ? null : json.readValue(attempt.getRunResultJson(), RunResult.class));
            AiModelRouter.Routed<ScenarioEvaluation> routed = router.generate(prompt, settings(ai.generation().maxOutputTokens(),
                    ai.generation().thinking()), validator::evaluation, problem -> prompts.repair(prompt, problem));
            String evaluationJson = json.writeValueAsString(routed.value());
            transaction.executeWithoutResult(tx -> {
                ScenarioAttempt fresh = attempts.findById(attemptId).orElseThrow();
                fresh.evaluated(evaluationJson, routed.provider(), routed.model());
                attempts.save(fresh);
            });
            log.info("Scenario evaluated: attempt={} verdict={} model={} attempts={}", attemptId, routed.value().verdict(), routed.model(),
                    routed.attempts());
        } catch (AiUnavailableException e) {
            markFailed(attemptId, e.code(), USER_MESSAGES.get(e.code()));
            log.warn("Scenario evaluation failed: attempt={} code={} detail={}", attemptId, e.code(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            markFailed(attemptId, "EVALUATION_FAILED", USER_MESSAGES.get("EVALUATION_FAILED"));
            log.error("Scenario evaluation failed unexpectedly: attempt={}", attemptId, e);
            return;
        }
        if (startFinalizing(attempt.getLabId())) {
            finalizeLab(attempt.getLabId());
        }
    }

    /** Completes the lab now if every scenario that exists is submitted and evaluated (e.g. when generation ends). */
    public void finalizeIfComplete(UUID labId) {
        if (startFinalizing(labId)) {
            finalizeLab(labId);
        }
    }

    public void markFailed(UUID attemptId, String code, String message) {
        transaction.executeWithoutResult(tx -> attempts.findById(attemptId).filter(a -> a.getEvaluationStatus() == EvaluationStatus.PENDING)
                .ifPresent(a -> {
                    a.evaluationFailed(code, message);
                    attempts.save(a);
                }));
    }

    // ---- the whole lab ----------------------------------------------------------------------------

    /** Moves the lab to FINALIZING when every scenario has an evaluated attempt. True for exactly one caller. */
    boolean startFinalizing(UUID labId) {
        try {
            return Boolean.TRUE.equals(transaction.execute(tx -> {
                ScenarioLab lab = labs.findById(labId).orElseThrow();
                if (lab.getStatus() != ScenarioLabStatus.ACTIVE) {
                    return false;
                }
                int total = scenarios.findByLabIdOrderByPositionAsc(labId).size();
                long evaluated = attempts.countByLabIdAndEvaluationStatusIn(labId, EnumSet.of(EvaluationStatus.COMPLETED));
                if (evaluated < total) {
                    return false;
                }
                lab.startFinalizing();
                labs.saveAndFlush(lab);
                return true;
            }));
        } catch (OptimisticLockingFailureException e) {
            return false; // another evaluation finishing at the same moment is finalising it
        }
    }

    /**
     * The user finishes the lab before submitting every scenario: the assessment covers what was submitted.
     * Every submitted answer must already be evaluated, so nothing in progress is lost or left behind.
     */
    public void finishEarly(UUID labId) {
        transaction.executeWithoutResult(tx -> {
            ScenarioLab lab = labs.findById(labId).orElseThrow();
            if (lab.getStatus() != ScenarioLabStatus.ACTIVE) {
                throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_LAB_NOT_ACTIVE", "This lab is no longer open.");
            }
            List<ScenarioAttempt> submitted = attempts.findByLabIdOrderByCreatedAtAsc(labId);
            if (submitted.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_LAB_NOTHING_SUBMITTED",
                        "Submit at least one scenario to finish the lab, or discard it.");
            }
            if (submitted.stream().anyMatch(a -> a.getEvaluationStatus() == EvaluationStatus.PENDING)) {
                throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_EVALUATIONS_PENDING",
                        "Some answers are still being evaluated. Finish the lab once they're done.");
            }
            if (submitted.stream().anyMatch(a -> a.getEvaluationStatus() == EvaluationStatus.FAILED)) {
                throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_EVALUATIONS_FAILED",
                        "An evaluation failed. Try it again before finishing the lab.");
            }
            lab.startFinalizing();
            labs.saveAndFlush(lab); // optimistic lock: a concurrent completion can't also finalise it
        });
        log.info("Scenario lab finished early: lab={}", labId);
        executor.execute(() -> finalizeLab(labId));
    }

    /** Lets a user retry finalising after it failed. */
    public void retryFinalization(UUID labId) {
        Boolean retry = transaction.execute(tx -> {
            ScenarioLab lab = labs.findById(labId).orElseThrow();
            if (lab.getStatus() != ScenarioLabStatus.FINALIZING || lab.getErrorCode() == null) {
                return false;
            }
            lab.retryFinalization();
            labs.save(lab);
            return true;
        });
        if (Boolean.TRUE.equals(retry)) {
            executor.execute(() -> finalizeLab(labId));
        }
    }

    void finalizeLab(UUID labId) {
        try {
            ScenarioLab lab = labs.findById(labId).orElseThrow();
            List<EvaluatedScenario> evaluated = evaluatedScenarios(lab);
            AiPrompt summaryPrompt = prompts.summarise(lab, evaluated, scenarios.findByLabIdOrderByPositionAsc(labId).size());
            AiModelRouter.Routed<LabSummary> summary = router.generate(summaryPrompt, settings(SUMMARY_MAX_OUTPUT_TOKENS, "low"),
                    validator::summary, problem -> prompts.repair(summaryPrompt, problem));
            LabTeaching teaching = teach(lab, summary.value(), evaluated);
            List<String> limitations = new ArrayList<>();
            int available = scenarios.findByLabIdOrderByPositionAsc(labId).size(); // fewer than requested if generation ended early
            if (available < lab.getScenarioCount()) {
                limitations.add("Only " + available + " of the " + lab.getScenarioCount() + " requested scenarios could be generated.");
            }
            if (evaluated.size() < available) {
                limitations.add("Finished early: " + evaluated.size() + " of " + available
                        + " scenarios were submitted, and only those are assessed.");
            }
            limitations.addAll(summary.value().limitations());
            if (teaching == null) {
                limitations.add(TEACHING_UNAVAILABLE);
            }
            Personalization personalization = teaching == null ? null : personalization(lab.getUserId());
            LabAssessment assessment = new LabAssessment(LabAssessment.SCHEMA_VERSION, summary.value().overallAssessment(),
                    summary.value().strengths(), summary.value().growthAreas(), teaching == null ? List.of() : teaching.scenarioLearning(),
                    teaching == null ? List.of() : teaching.learningRecommendations(), List.copyOf(limitations),
                    personalization == null ? null : personalization.basis());
            String assessmentJson = json.writeValueAsString(assessment);
            transaction.executeWithoutResult(tx -> {
                ScenarioLab fresh = labs.findById(labId).orElseThrow();
                assessments.save(new ScenarioLabAssessment(labId, assessmentJson, summary.provider(), summary.model()));
                fresh.complete(); // frees the open-lab slot: Scenario Lab is empty again
                labs.save(fresh);
            });
            log.info("Scenario lab completed: lab={} scenarios={} model={} personalised={}", labId, evaluated.size(), summary.model(),
                    teaching != null);
        } catch (AiUnavailableException e) {
            finalizationFailed(labId, e.code(), USER_MESSAGES.get(e.code()));
            log.warn("Scenario lab finalisation failed: lab={} code={}", labId, e.code());
        } catch (RuntimeException e) {
            finalizationFailed(labId, "EVALUATION_FAILED", USER_MESSAGES.get("EVALUATION_FAILED"));
            log.error("Scenario lab finalisation failed unexpectedly: lab={}", labId, e);
        }
    }

    /** Best effort, like the review's teaching step: without it the assessment still completes. */
    private LabTeaching teach(ScenarioLab lab, LabSummary summary, List<EvaluatedScenario> evaluated) {
        Personalization p = personalization(lab.getUserId());
        DeveloperProfile developer = profiles.findById(lab.getUserId()).map(DeveloperProfile::from).orElse(null);
        AiPrompt prompt = prompts.teach(personalizer.guidance(p), developer == null ? "no profile" : json.writeValueAsString(developer),
                p.audience() + " (" + p.basis() + ")", summary, evaluated);
        Set<String> ids = evaluated.stream().map(EvaluatedScenario::scenarioId).collect(Collectors.toSet());
        try {
            return router.generate(prompt, settings(SUMMARY_MAX_OUTPUT_TOKENS, "low"), raw -> validator.teaching(raw, ids),
                    problem -> prompts.repair(prompt, problem)).value();
        } catch (RuntimeException e) {
            log.warn("Scenario lab teaching unavailable: lab={} reason={}", lab.getId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private Personalization personalization(UUID userId) {
        return personalizer.personalize(profiles.findById(userId).map(DeveloperProfile::from).orElse(null));
    }

    private List<EvaluatedScenario> evaluatedScenarios(ScenarioLab lab) {
        Map<UUID, ScenarioAttempt> byScenario = attempts.findByLabIdOrderByCreatedAtAsc(lab.getId()).stream()
                .collect(Collectors.toMap(ScenarioAttempt::getScenarioId, Function.identity()));
        List<EvaluatedScenario> list = new ArrayList<>();
        for (Scenario s : scenarios.findByLabIdOrderByPositionAsc(lab.getId())) {
            ScenarioAttempt a = byScenario.get(s.getId());
            if (a == null) {
                continue; // finished early: unsubmitted scenarios aren't assessed
            }
            RunResult run = a.getRunResultJson() == null ? null : json.readValue(a.getRunResultJson(), RunResult.class);
            list.add(new EvaluatedScenario(s.getId().toString(), s.getTitle(), s.getCategory().name(), a.getMode().name(),
                    run == null ? "not run" : run.status() + " " + run.passed() + "/" + run.total(),
                    json.readValue(a.getEvaluationJson(), ScenarioEvaluation.class)));
        }
        return list;
    }

    private void finalizationFailed(UUID labId, String code, String message) {
        try {
            transaction.executeWithoutResult(tx -> labs.findById(labId).filter(l -> l.getStatus() == ScenarioLabStatus.FINALIZING)
                    .ifPresent(l -> {
                        l.finalizationFailed(code, message);
                        labs.save(l);
                    }));
        } catch (OptimisticLockingFailureException e) {
            log.info("Lab {} changed while recording its finalisation failure", labId);
        }
    }

    private AiGenerationSettings settings(int maxOutputTokens, String thinking) {
        return new AiGenerationSettings(ai.generation().temperature(), maxOutputTokens, thinking, true);
    }

    /** Work cut short by a restart: evaluations become retryable failures, and finalising labs can be retried. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedWork() {
        List<ScenarioAttempt> pending = attempts.findByEvaluationStatus(EvaluationStatus.PENDING);
        pending.forEach(a -> markFailed(a.getId(), "INTERRUPTED", USER_MESSAGES.get("INTERRUPTED")));
        List<ScenarioLab> finalizing = labs.findByStatusIn(EnumSet.of(ScenarioLabStatus.FINALIZING)).stream()
                .filter(l -> l.getErrorCode() == null).toList();
        finalizing.forEach(l -> finalizationFailed(l.getId(), "INTERRUPTED", USER_MESSAGES.get("INTERRUPTED")));
        if (!pending.isEmpty() || !finalizing.isEmpty()) {
            log.warn("After restart: {} evaluation(s) and {} lab finalisation(s) marked for retry", pending.size(), finalizing.size());
        }
    }
}
