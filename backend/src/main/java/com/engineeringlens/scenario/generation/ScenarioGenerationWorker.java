package com.engineeringlens.scenario.generation;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.engineeringlens.analysis.ai.AiUnavailableException;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.Scenario;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.ScenarioLabRepository;
import com.engineeringlens.scenario.ScenarioLabStatus;
import com.engineeringlens.scenario.ScenarioRepository;
import com.engineeringlens.scenario.evaluation.EvaluationWorker;
import com.engineeringlens.scenario.generation.ScenarioGenerator.PreparedScenario;
import com.engineeringlens.scenario.model.ScenarioDocument;
import com.engineeringlens.scenario.model.ScenarioHarness;
import com.engineeringlens.scenario.model.ScenarioReference;
import com.engineeringlens.scenario.model.ScenarioWorkspace;

import tools.jackson.databind.ObjectMapper;

/**
 * Generates one lab off the request thread. Each scenario is saved as soon as it is proven, together with
 * the lab's progress counter, so "3 of 5 ready" is real. The lab opens (ACTIVE) only when all are saved;
 * on failure it becomes FAILED, which frees the user's open-lab slot. A lab cancelled meanwhile stops it.
 */
@Component
public class ScenarioGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(ScenarioGenerationWorker.class);

    static final Map<String, String> USER_MESSAGES = Map.of(
            AiUnavailableException.NOT_CONFIGURED, "Scenario generation isn't set up on this server yet.",
            AiUnavailableException.UNAVAILABLE, "No AI model is available right now. Please try again in a few minutes.",
            AiUnavailableException.REQUEST_REJECTED, "The scenario request couldn't be processed. Please try again later.",
            "SCENARIO_GENERATION_FAILED", "No working scenario could be generated for this repository this time. Please try again.",
            "INTERRUPTED", "Generating this lab was interrupted by a server restart. Please start it again.");

    private final ScenarioLabRepository labs;
    private final ScenarioRepository scenarios;
    private final ScenarioGenerator generator;
    private final EvaluationWorker evaluation;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public ScenarioGenerationWorker(ScenarioLabRepository labs, ScenarioRepository scenarios, ScenarioGenerator generator,
            EvaluationWorker evaluation, ObjectMapper json, PlatformTransactionManager tm) {
        this.labs = labs;
        this.scenarios = scenarios;
        this.generator = generator;
        this.evaluation = evaluation;
        this.json = json;
        this.transaction = new TransactionTemplate(tm);
    }

    public void execute(UUID labId) {
        ScenarioLab lab = labs.findById(labId).orElse(null);
        if (lab == null || lab.getStatus() != ScenarioLabStatus.GENERATING) {
            return;
        }
        long start = System.nanoTime();
        log.info("Scenario generation started: lab={} count={} roles={} seniority={}", labId, lab.getScenarioCount(), lab.getRoles(),
                lab.getSeniority());
        try {
            ScenarioGenerator.Outcome outcome = generator.generate(lab, new ScenarioGenerator.Sink() {
                @Override
                public boolean accept(PreparedScenario prepared) {
                    return save(labId, prepared);
                }

                @Override
                public void planned() {
                    update(labId, ScenarioLab::startBuilding);
                }

                @Override
                public void rejected() {
                    update(labId, ScenarioLab::scenarioRejected);
                }
            });
            end(labId, outcome);
            log.info("Scenario generation finished: lab={} produced={}/{} stopReason={} durationMs={}", labId, outcome.produced(),
                    outcome.wanted(), outcome.stopReason(), (System.nanoTime() - start) / 1_000_000);
        } catch (OptimisticLockingFailureException e) {
            log.info("Scenario generation stopped: lab={} changed concurrently (cancelled)", labId);
        } catch (AiUnavailableException e) {
            fail(labId, e.code(), USER_MESSAGES.get(e.code()));
            log.warn("Scenario generation failed: lab={} code={} detail={}", labId, e.code(), e.getMessage());
        } catch (ApiException e) {
            fail(labId, e.getCode(), e.getMessage()); // already user-safe (GitHub re-read...)
            log.warn("Scenario generation failed: lab={} code={}", labId, e.getCode());
        } catch (RuntimeException e) {
            fail(labId, "SCENARIO_GENERATION_FAILED", USER_MESSAGES.get("SCENARIO_GENERATION_FAILED"));
            log.error("Scenario generation failed unexpectedly: lab={}", labId, e);
        }
    }

    /**
     * Generation is over. Every requested scenario: the lab is simply active. Some: active, with a note saying how
     * many exist. None: the lab fails, because an empty lab is not a usable lab. If the user already submitted every
     * scenario that exists, the lab is completed now (it couldn't be while generating).
     */
    private void end(UUID labId, ScenarioGenerator.Outcome outcome) {
        if (outcome.produced() == 0) {
            AiUnavailableException outage = outcome.outage();
            fail(labId, outage != null ? outage.code() : "SCENARIO_GENERATION_FAILED",
                    outage != null ? USER_MESSAGES.get(outage.code()) : USER_MESSAGES.get("SCENARIO_GENERATION_FAILED"));
            return;
        }
        String note = outcome.complete() ? null : outcome.produced() + " of " + outcome.wanted() + " scenarios generated.";
        boolean opened = withRetry(() -> Boolean.TRUE.equals(transaction.execute(tx -> {
            ScenarioLab current = labs.findById(labId).orElseThrow();
            if (current.getStatus() != ScenarioLabStatus.GENERATING) {
                return false;
            }
            current.activate(note);
            labs.save(current);
            return true;
        })));
        if (opened) {
            evaluation.finalizeIfComplete(labId);
        }
    }

    /**
     * Saves one proven scenario and advances progress atomically; false when the lab is no longer generating.
     * Builds finish in parallel, so two saves can race for the next position: the lab's optimistic lock and the
     * unique (lab, position) constraint reject the loser, which simply tries again with a fresh count.
     */
    private boolean save(UUID labId, PreparedScenario prepared) {
        return withRetry(() -> Boolean.TRUE.equals(transaction.execute(tx -> {
            ScenarioLab lab = labs.findById(labId).orElseThrow();
            if (lab.getStatus() != ScenarioLabStatus.GENERATING) {
                return false;
            }
            scenarios.saveAndFlush(toEntity(lab, lab.getScenariosReady() + 1, prepared));
            lab.scenarioReady();
            labs.saveAndFlush(lab);
            return true;
        })));
    }

    /** A small change to a generating lab (stage, rejection count), retried on a concurrent update. */
    private void update(UUID labId, java.util.function.Consumer<ScenarioLab> change) {
        withRetry(() -> Boolean.TRUE.equals(transaction.execute(tx -> {
            ScenarioLab lab = labs.findById(labId).orElseThrow();
            if (lab.getStatus() != ScenarioLabStatus.GENERATING) {
                return false;
            }
            change.accept(lab);
            labs.saveAndFlush(lab);
            return true;
        })));
    }

    private static final int SAVE_ATTEMPTS = 8;

    private boolean withRetry(java.util.function.BooleanSupplier write) {
        for (int attempt = 1;; attempt++) {
            try {
                return write.getAsBoolean();
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (attempt >= SAVE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /** Splits the validated scenario into its public, workspace, hidden and reference parts. */
    Scenario toEntity(ScenarioLab lab, int position, PreparedScenario prepared) {
        GeneratedScenario g = prepared.scenario();
        ScenarioPlan.Outline o = prepared.outline();
        boolean code = o.mode() == ExecutionCapability.CODE;
        ScenarioDocument document = new ScenarioDocument(Scenario.SCHEMA_VERSION, g.title(), g.summary(), g.incident(), g.context(), g.task(),
                g.expectedBehaviour(), g.constraints(),
                g.evidence().stream().map(e -> new ScenarioDocument.Evidence(e.file(), e.lineStart(), e.lineEnd(), e.explanation())).toList());
        ScenarioReference reference = new ScenarioReference(g.expectedConcepts(),
                g.rubric().stream().map(c -> new ScenarioReference.RubricCriterion(c.criterion(), c.whatGoodLooksLike())).toList(),
                g.referenceReasoning(), code ? new ScenarioReference.Solution(
                        g.referenceSolution().files().stream().map(f -> new ScenarioReference.File(f.path(), f.content())).toList(),
                        g.referenceSolution().explanation()) : null);
        String workspace = code ? json.writeValueAsString(new ScenarioWorkspace(g.workspace().language(),
                g.workspace().files().stream().map(f -> new ScenarioWorkspace.File(f.path(), f.content(), f.editable())).toList())) : null;
        String harness = code ? json.writeValueAsString(new ScenarioHarness(g.checks().source(), g.checks().checkNames())) : null;
        return new Scenario(lab.getId(), position, o.role(), lab.getSeniority(), o.category(), o.difficulty(), o.mode(),
                code ? o.language() : null, g.title(), json.writeValueAsString(document), workspace, harness,
                json.writeValueAsString(reference), prepared.validation() == null ? null : json.writeValueAsString(prepared.validation()));
    }

    private void fail(UUID labId, String code, String message) {
        try {
            transaction.executeWithoutResult(tx -> labs.findById(labId).filter(l -> l.getStatus().open()).ifPresent(l -> {
                l.fail(code, message);
                labs.save(l);
            }));
        } catch (OptimisticLockingFailureException e) {
            log.info("Lab {} changed while recording its failure", labId);
        }
    }

    /**
     * Generation cut short by a restart will never finish. Scenarios already validated (and maybe already worked on)
     * are kept: the lab opens with what it has. Only a lab with none at all fails, so the user can start again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedGeneration() {
        List<ScenarioLab> stuck = labs.findByStatusIn(EnumSet.of(ScenarioLabStatus.GENERATING));
        for (ScenarioLab lab : stuck) {
            if (lab.getScenariosReady() > 0) {
                end(lab.getId(), new ScenarioGenerator.Outcome(lab.getScenariosReady(), lab.getScenarioCount(), "INTERRUPTED", null));
            } else {
                fail(lab.getId(), "INTERRUPTED", USER_MESSAGES.get("INTERRUPTED"));
            }
        }
        if (!stuck.isEmpty()) {
            log.warn("Ended {} scenario lab generation(s) interrupted by a restart", stuck.size());
        }
    }
}
