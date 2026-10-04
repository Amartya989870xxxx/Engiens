package com.engineeringlens.scenario.model;

import java.util.List;

/**
 * What a strong answer looks like. Private while the lab is open; shown in the read-only assessment once
 * the scenario is submitted. One valid solution among many: evaluation never requires matching it.
 *
 * @param solution null for APPROACH_ONLY scenarios
 */
public record ScenarioReference(List<String> expectedConcepts, List<RubricCriterion> rubric, String referenceReasoning,
        Solution solution) {

    public record RubricCriterion(String criterion, String whatGoodLooksLike) {
    }

    /** Full contents of the workspace files the reference changes. */
    public record Solution(List<File> files, String explanation) {
    }

    public record File(String path, String content) {
    }
}
