package com.engineeringlens.scenario.model;

/**
 * Proof that an executable scenario works before anyone sees it: the starter runs but fails at least one
 * check (it reproduces the problem), and the reference passes them all, within the sandbox's limits.
 */
public record ScenarioValidation(Outcome starter, Outcome reference, String validatedAt) {

    public record Outcome(String status, int passed, int total, long durationMs) {
    }
}
