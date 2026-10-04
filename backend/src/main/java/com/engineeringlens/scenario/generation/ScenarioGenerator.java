package com.engineeringlens.scenario.generation;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.ai.AiGenerationSettings;
import com.engineeringlens.analysis.ai.AiModelRouter;
import com.engineeringlens.analysis.ai.AiPrompt;
import com.engineeringlens.analysis.ai.AiProperties;
import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioLab;
import com.engineeringlens.scenario.execution.ScenarioExecutionService;
import com.engineeringlens.scenario.generation.ScenarioContextBuilder.ScenarioContext;
import com.engineeringlens.scenario.model.ScenarioValidation;

/**
 * Generates a lab's scenarios: plan once over the selected context, then build each scenario from only its
 * own files, validate it, and (for executable ones) prove its harness in the sandbox. A scenario that can't
 * be proven gets one repair; if it still fails, a spare outline takes its place; only when spares run out
 * does a failed code scenario come back as an approach-only one. A broken scenario is never published.
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

    public ScenarioGenerator(ScenarioContextBuilder contexts, ScenarioPromptBuilder prompts, ScenarioOutputValidator validator,
            HarnessValidator harness, ScenarioExecutionService execution, AiModelRouter router, AiProperties ai) {
        this.contexts = contexts;
        this.prompts = prompts;
        this.validator = validator;
        this.harness = harness;
        this.execution = execution;
        this.router = router;
        this.ai = ai;
    }

    /** A scenario ready to be saved: the outline it came from, the validated content, and proof it runs (CODE only). */
    public record PreparedScenario(ScenarioPlan.Outline outline, GeneratedScenario scenario, ScenarioValidation validation) {
    }

    /** Receives each scenario as soon as it's ready; returns false to stop (e.g. the lab was cancelled). */
    @FunctionalInterface
    public interface Sink {
        boolean accept(PreparedScenario scenario);
    }

    /** Spare outlines beyond the requested count: replacements for scenarios that fail validation. */
    static int spares(int wanted) {
        return Math.max(2, (int) Math.ceil(wanted * 0.4));
    }

    public void generate(ScenarioLab lab, Sink sink) {
        boolean sandbox = execution.available();
        ScenarioContext ctx = contexts.build(lab, sandbox);
        int wanted = lab.getScenarioCount();
        AiPrompt planPrompt = prompts.plan(ctx, lab, wanted + spares(wanted));
        AiModelRouter.Routed<ScenarioPlan> plan = router.generate(planPrompt, settings(PLAN_MAX_OUTPUT_TOKENS),
                raw -> validator.plan(raw, lab, ctx, wanted), problem -> prompts.repair(planPrompt, problem));
        log.info("Scenario plan ready: lab={} outlines={} executableLanguages={} model={} attempts={}", lab.getId(),
                plan.value().outlines().size(), ctx.executableLanguages(), plan.model(), plan.attempts());

        int produced = 0;
        List<ScenarioPlan.Outline> failedCode = new ArrayList<>();
        for (ScenarioPlan.Outline outline : plan.value().outlines()) {
            if (produced == wanted) {
                break;
            }
            PreparedScenario prepared = build(ctx, lab, outline);
            if (prepared == null) {
                failedCode.add(outline);
                continue;
            }
            if (!sink.accept(prepared)) {
                return;
            }
            produced++;
        }
        // Spares are used first; only then do code scenarios that couldn't be proven come back as approach-only.
        for (ScenarioPlan.Outline outline : failedCode) {
            if (produced == wanted) {
                break;
            }
            PreparedScenario prepared = build(ctx, lab, outline.withMode(ExecutionCapability.APPROACH_ONLY, null));
            if (prepared != null) {
                if (!sink.accept(prepared)) {
                    return;
                }
                produced++;
            }
        }
        if (produced < wanted) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SCENARIO_GENERATION_FAILED",
                    "We couldn't generate enough working scenarios for this repository. Please try again.");
        }
    }

    /**
     * Builds and validates one scenario. Returns null when an executable scenario couldn't be proven to work
     * (after one repair). AI outages propagate: they fail the whole lab rather than silently degrading it.
     */
    private PreparedScenario build(ScenarioContext ctx, ScenarioLab lab, ScenarioPlan.Outline outline) {
        AiPrompt prompt = prompts.build(ctx, lab, outline);
        AiModelRouter.Routed<GeneratedScenario> built = router.generate(prompt, settings(ai.generation().maxOutputTokens()),
                raw -> validator.scenario(raw, outline, ctx), problem -> prompts.repair(prompt, problem));
        if (outline.mode() != ExecutionCapability.CODE) {
            log.info("Scenario built: lab={} outline={} mode=APPROACH_ONLY model={}", lab.getId(), outline.key(), built.model());
            return new PreparedScenario(outline, built.value(), null);
        }
        try {
            return new PreparedScenario(outline, built.value(), harness.validate(built.value()));
        } catch (HarnessValidator.HarnessRejected first) {
            log.info("Scenario harness rejected, repairing once: lab={} outline={} reason={}", lab.getId(), outline.key(),
                    first.getMessage().length() > 200 ? first.getMessage().substring(0, 200) : first.getMessage());
            AiPrompt repair = prompts.repair(prompt, "The scenario failed execution validation in the sandbox: " + first.getMessage());
            try {
                AiModelRouter.Routed<GeneratedScenario> again = router.generate(repair, settings(ai.generation().maxOutputTokens()),
                        raw -> validator.scenario(raw, outline, ctx), problem -> prompts.repair(repair, problem));
                return new PreparedScenario(outline, again.value(), harness.validate(again.value()));
            } catch (HarnessValidator.HarnessRejected second) {
                log.warn("Scenario harness still invalid after repair, skipping: lab={} outline={}", lab.getId(), outline.key());
                return null;
            }
        } catch (HarnessValidator.SandboxUnavailable e) {
            log.warn("Sandbox unavailable while validating: lab={} outline={}", lab.getId(), outline.key());
            return null;
        }
    }

    private AiGenerationSettings settings(int maxOutputTokens) {
        return new AiGenerationSettings(ai.generation().temperature(), maxOutputTokens, ai.generation().thinking(), true);
    }
}
