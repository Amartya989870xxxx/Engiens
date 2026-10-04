package com.engineeringlens.scenario.model;

import java.util.List;

/**
 * The public part of a scenario: what the user reads before solving it. Never contains the hidden checks,
 * the rubric, expected concepts or the reference (those are in {@link ScenarioReference}), so returning
 * this to the browser can't give the answer away.
 *
 * @param summary one or two sentences, for lists and the PDF
 * @param incident what is happening (symptoms, impact), in this repository's terms
 * @param context  the relevant part of this repository's architecture
 * @param task     what the user must do
 */
public record ScenarioDocument(int scenarioSchemaVersion, String title, String summary, String incident, String context, String task,
        List<String> expectedBehaviour, List<String> constraints, List<Evidence> evidence) {

    /** A place in the repository at the lab's commit that this scenario is based on. */
    public record Evidence(String file, Integer lineStart, Integer lineEnd, String explanation) {
    }
}
