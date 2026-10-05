package com.engineeringlens.scenario.generation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits for generating a lab, in one place. Concurrency is global (across all labs); budgets are per lab and grow
 * with its size, so a 20-scenario lab can't run unbounded AI calls or time.
 *
 * @param buildConcurrency       scenarios built at the same time, across all labs (each build = AI call + sandbox runs)
 * @param buildCallsPerScenario  hard cap on AI build calls per requested scenario, harness repairs, spares and fallbacks
 *                               included. 4 equals the pipeline's own worst case (2 calls per outline incl. spares,
 *                               plus approach fallbacks), so it never cuts a normal lab short; typical use is 1.5–2.
 * @param deadlineBase           time allowed for any lab ...
 * @param deadlinePerScenario    ... plus this much per requested scenario; no new build starts after the deadline
 * @param diversityFromCount     labs at least this large cap outlines per category and per main file
 */
@ConfigurationProperties("scenario.generation")
public record GenerationProperties(@DefaultValue("3") int buildConcurrency, @DefaultValue("4") double buildCallsPerScenario,
        @DefaultValue("PT10M") Duration deadlineBase, @DefaultValue("PT90S") Duration deadlinePerScenario,
        @DefaultValue("10") int diversityFromCount) {

    public int buildCallBudget(int scenarios) {
        return (int) Math.ceil(scenarios * buildCallsPerScenario);
    }

    public Duration deadline(int scenarios) {
        return deadlineBase.plus(deadlinePerScenario.multipliedBy(scenarios));
    }
}
