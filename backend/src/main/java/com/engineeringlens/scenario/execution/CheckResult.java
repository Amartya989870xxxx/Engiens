package com.engineeringlens.scenario.execution;

/** One named check: whether it passed and, if not, why (an assertion message or the user's error). */
public record CheckResult(String name, boolean passed, String message, long durationMs) {
}
