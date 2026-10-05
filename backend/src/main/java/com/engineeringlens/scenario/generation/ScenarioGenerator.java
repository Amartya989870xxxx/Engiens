package com.engineeringlens.scenario.generation;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.AiGenerationSettings;
import com.engineeringlens.analysis.ai.AiModelRouter;
import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.ai.AiProperties;
import com.engineeringlens.analysis.ai.AiUnavailableException;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.execution.ScenarioExecutionService;
import com.engineeringlens.scenario.generation.ScenarioContextBuilder.ScenarioContext;
import com.engineeringlens.scenario.model.ScenarioValidation;

/**
 * Generates a lab's scenarios: plan once over the selected context, then build each scenario from only its
 * own files, validate it, and (for executable ones) prove its harness in the sandbox. A scenario that can't
 * be proven gets its repairs ({@link GenerationProperties#harnessRepairs()}); if it still fails, a spare outline takes its place; only when spares run out
 * does a failed code scenario come back as an approach-only one. A broken scenario is never published.
 *
 * <p>Builds run in parallel within {@link GenerationProperties#buildConcurrency()} (a global, bounded pool), and
 * each scenario is handed over as soon as it is proven, so the user can start on it while the rest are built.
 * A lab never spends more than its build-call budget or time budget; when either runs out, or no AI model is
 * available, generation ends with what it has ({@link Outcome}).
 */
@Component
public class ScenarioGenerator {

    private static final Logger log = LoggerFactory.getLogger(ScenarioGenerator.class);
    private static final int PLAN_MAX_OUTPUT_TOKENS = 16_384;

    private final ScenarioContextBuilder contexts;
    private final ScenarioPromptBuilder prompts;
    private final ScenarioOutputValidator validator;
    private final HarnessValidator harness;
    private final ScenarioExecutionService execution;
    private final AiModelRouter router;
    private final AiProperties ai;
    private final GenerationProperties limits;
    private final Executor builds;

    public ScenarioGenerator(ScenarioContextBuilder contexts, ScenarioPromptBuilder prompts, ScenarioOutputValidator validator,
            HarnessValidator harness, ScenarioExecutionService execution, AiModelRouter router, AiProperties ai,
            GenerationProperties limits, @Qualifier("scenarioBuildExecutor") Executor builds) {
        this.contexts = contexts;
        this.prompts = prompts;
        this.validator = validator;
        this.harness = harness;
        this.execution = execution;
        this.router = router;
        this.ai = ai;
        this.limits = limits;
        this.builds = builds;
    }

    /** A scenario ready to be saved: the outline it came from, the validated content, and proof it runs (CODE only). */
    public record PreparedScenario(ScenarioPlan.Outline outline, GeneratedScenario scenario, ScenarioValidation validation) {
    }

    /** Receives generation events. Called from build threads, so implementations must be thread-safe. */
    @FunctionalInterface
    public interface Sink {
        /** A scenario is proven: save it and make it available. Returns false to stop (e.g. the lab was cancelled). */
        boolean accept(PreparedScenario scenario);

        /** The plan is written; building starts. */
        default void planned() {
        }

        /** A code scenario failed validation even after its repair and will be replaced. */
        default void rejected() {
        }
    }

    /**
     * How generation ended.
     *
     * @param stopReason null when every requested scenario was produced; otherwise why it stopped early
     * @param outage     the AI outage that stopped it, if that's why (its code is shown when nothing was produced)
     */
    public record Outcome(int produced, int wanted, String stopReason, AiUnavailableException outage) {
        public boolean complete() {
            return produced >= wanted;
        }
    }

    public Outcome generate(ScenarioLab lab, Sink sink) {
        boolean sandbox = execution.available();
        ScenarioContext ctx = contexts.build(lab, sandbox);
        if (!ScenarioFit.hasCodeFor(lab, ctx.loaded().context().contents().keySet())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NO_CODE_FOR_ROLES", "The code selected from this repository has "
                    + "nothing the chosen roles work on (for example, no frontend code for a Frontend Engineer lab). Choose roles "
                    + "that match what this repository contains.");
        }
        int wanted = lab.getScenarioCount();
        AiPrompt planPrompt = prompts.plan(ctx, lab, wanted + limits.spares(wanted));
        AiModelRouter.Routed<ScenarioPlan> plan = router.generate(planPrompt, settings(PLAN_MAX_OUTPUT_TOKENS),
                raw -> validator.plan(raw, lab, ctx, wanted), problem -> prompts.repair(planPrompt, problem));
        log.info("Scenario plan ready: lab={} outlines={} executableLanguages={} model={} attempts={}", lab.getId(),
                plan.value().outlines().size(), ctx.executableLanguages(), plan.model(), plan.attempts());
        sink.planned();

        Run run = new Run(ctx, lab, plan.value().outlines(), sink, limits.buildCallBudget(wanted),
                Instant.now().plus(limits.deadline(wanted)));
        int workers = Math.max(1, Math.min(limits.buildConcurrency(), plan.value().outlines().size()));
        CompletableFuture<?>[] running = new CompletableFuture<?>[workers];
        for (int i = 0; i < workers; i++) {
            running[i] = CompletableFuture.runAsync(run::work, builds);
        }
        CompletableFuture.allOf(running).join();
        Outcome outcome = run.outcome();
        log.info("Scenario builds ended: lab={} produced={}/{} stopReason={} buildCallsLeft={}", lab.getId(), outcome.produced(),
                wanted, outcome.stopReason(), run.callsLeft);
        return outcome;
    }

    /**
     * One lab's build work, shared by its workers. Outlines are taken in plan order (primaries, then spares, then
     * approach-only versions of code outlines that couldn't be proven); a worker only starts a build if it could
     * still be needed (produced + in flight < wanted), so parallelism never overshoots the requested count.
     */
    private final class Run {
        private final ScenarioContext ctx;
        private final ScenarioLab lab;
        private final Sink sink;
        private final int wanted;
        private final Instant deadline;
        private final Deque<ScenarioPlan.Outline> outlines;
        private final Deque<ScenarioPlan.Outline> fallbacks = new ArrayDeque<>();
        private int callsLeft;
        private int produced;
        private int inFlight;
        private boolean stopped;
        private String stopReason;
        private AiUnavailableException outage;

        Run(ScenarioContext ctx, ScenarioLab lab, List<ScenarioPlan.Outline> plan, Sink sink, int callBudget, Instant deadline) {
            this.ctx = ctx;
            this.lab = lab;
            this.sink = sink;
            this.wanted = lab.getScenarioCount();
            this.outlines = new ArrayDeque<>(plan);
            this.callsLeft = callBudget;
            this.deadline = deadline;
        }

        void work() {
            for (ScenarioPlan.Outline outline; (outline = next()) != null;) {
                PreparedScenario prepared = null;
                try {
                    prepared = build(ctx, lab, outline, this::reserveCall);
                } catch (AiUnavailableException e) {
                    stop(e.code(), e);
                } catch (RuntimeException e) {
                    log.error("Scenario build failed unexpectedly: lab={} outline={}", lab.getId(), outline.key(), e);
                }
                boolean accepted = prepared != null && sink.accept(prepared);
                finished(outline, prepared, accepted);
            }
        }

        private synchronized ScenarioPlan.Outline next() {
            if (stopped || produced + inFlight >= wanted) {
                return null;
            }
            if (Instant.now().isAfter(deadline)) {
                stop("TIME_BUDGET", null);
                return null;
            }
            ScenarioPlan.Outline next = !outlines.isEmpty() ? outlines.poll() : fallbacks.poll();
            if (next == null) {
                return null;
            }
            if (!reserveCall()) {
                return null;
            }
            inFlight++;
            return next;
        }

        /** One AI build call (the build itself or its harness repair), if the lab's budget allows it. */
        private synchronized boolean reserveCall() {
            if (callsLeft <= 0) {
                stop("CALL_BUDGET", null);
                return false;
            }
            callsLeft--;
            return true;
        }

        private void finished(ScenarioPlan.Outline outline, PreparedScenario prepared, boolean accepted) {
            boolean rejectedCode = prepared == null && outline.mode() == ExecutionCapability.CODE && !isStopped();
            if (rejectedCode) {
                sink.rejected();
            }
            synchronized (this) {
                inFlight--;
                if (accepted) {
                    produced++;
                } else if (prepared != null) {
                    stopped = true; // the sink refused: the lab was cancelled or closed
                    stopReason = stopReason == null ? "LAB_CLOSED" : stopReason;
                } else if (rejectedCode) {
                    // Spares come first; only then does an unprovable code scenario come back as approach-only.
                    fallbacks.add(outline.withMode(ExecutionCapability.APPROACH_ONLY, null));
                }
            }
            // A worker that ran out of work exits; the worker finishing this build loops and picks up any fallback.
        }

        private synchronized boolean isStopped() {
            return stopped;
        }

        private synchronized void stop(String reason, AiUnavailableException cause) {
            if (!stopped) {
                stopped = true;
                stopReason = reason;
                outage = cause;
            }
        }

        synchronized Outcome outcome() {
            String reason = produced >= wanted ? null : stopReason != null ? stopReason : "OUTLINES_EXHAUSTED";
            return new Outcome(produced, wanted, reason, outage);
        }
    }

    /**
     * Builds and validates one scenario. Returns null when an executable scenario couldn't be proven to work
     * (after one repair). AI outages propagate: they fail the whole lab rather than silently degrading it.
     */
    private PreparedScenario build(ScenarioContext ctx, ScenarioLab lab, ScenarioPlan.Outline outline,
            java.util.function.BooleanSupplier reserveRepairCall) {
        AiPrompt prompt = prompts.build(ctx, lab, outline);
        AiModelRouter.Routed<GeneratedScenario> built = router.generate(prompt, settings(ai.generation().maxOutputTokens()),
                raw -> validator.scenario(raw, outline, ctx), problem -> prompts.repair(prompt, problem));
        if (outline.mode() != ExecutionCapability.CODE) {
            log.info("Scenario built: lab={} outline={} mode=APPROACH_ONLY model={}", lab.getId(), outline.key(), built.model());
            return new PreparedScenario(outline, built.value(), null);
        }
        GeneratedScenario candidate = built.value();
        AiPrompt current = prompt;
        for (int attempt = 0;; attempt++) {
            try {
                return new PreparedScenario(outline, candidate, harness.validate(candidate));
            } catch (HarnessValidator.HarnessRejected rejected) {
                if (attempt >= limits.harnessRepairs()) {
                    log.warn("Scenario harness still invalid after {} repair(s), skipping: lab={} outline={}", attempt, lab.getId(),
                            outline.key());
                    return null;
                }
                if (!reserveRepairCall.getAsBoolean()) {
                    log.info("Scenario harness rejected and no build calls left for a repair: lab={} outline={}", lab.getId(),
                            outline.key());
                    return null;
                }
                log.info("Scenario harness rejected, repairing: lab={} outline={} reason={}", lab.getId(), outline.key(),
                        rejected.getMessage().length() > 200 ? rejected.getMessage().substring(0, 200) : rejected.getMessage());
                AiPrompt repair = prompts.repair(current, "The scenario failed execution validation in the sandbox: " + rejected.getMessage());
                candidate = router.generate(repair, settings(ai.generation().maxOutputTokens()),
                        raw -> validator.scenario(raw, outline, ctx), problem -> prompts.repair(repair, problem)).value();
                current = repair;
            } catch (HarnessValidator.SandboxUnavailable e) {
                log.warn("Sandbox unavailable while validating: lab={} outline={}", lab.getId(), outline.key());
                return null;
            }
        }
    }

    private AiGenerationSettings settings(int maxOutputTokens) {
        return new AiGenerationSettings(ai.generation().temperature(), maxOutputTokens, ai.generation().thinking(), true);
    }
}
