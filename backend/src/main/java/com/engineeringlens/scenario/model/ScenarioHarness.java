package com.engineeringlens.scenario.model;

import java.util.List;

/** The hidden checks. Never sent to the browser; only their names and results are shown. */
public record ScenarioHarness(String checksSource, List<String> checkNames) {
}
