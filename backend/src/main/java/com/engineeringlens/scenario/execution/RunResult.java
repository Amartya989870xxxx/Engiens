package com.engineeringlens.scenario.execution;

import java.util.List;

/**
 * Objective execution feedback ("4 / 6 checks passed"), never a grade. Persisted with an attempt, so all
 * text is capped.
 *
 * @param message a one-line explanation for COMPILE_ERROR, RUNTIME_ERROR, TIMEOUT and LIMIT_EXCEEDED
 */
public record RunResult(RunStatus status, int passed, int total, long durationMs, List<CheckResult> checks, String message,
        String stdout, String stderr, boolean outputTruncated) {
}
