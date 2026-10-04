package com.engineeringlens.scenario.generation;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.execution.CheckResult;
import com.engineeringlens.scenario.execution.RunResult;
import com.engineeringlens.scenario.execution.RunStatus;
import com.engineeringlens.scenario.execution.ScenarioExecutionService;
import com.engineeringlens.scenario.execution.WorkspaceFile;
import com.engineeringlens.scenario.model.ScenarioValidation;

/**
 * Proves an executable scenario works before anyone sees it, by running it in the sandbox (no network):
 * <ol>
 * <li>the starter workspace loads/compiles and runs, but fails at least one check: it reproduces the problem;</li>
 * <li>the checks that ran are exactly the declared ones;</li>
 * <li>the reference solution passes every check, within the time and memory limits.</li>
 * </ol>
 * The user is never the test suite for the generator.
 */
@Component
public class HarnessValidator {

    /** Validation runs in the background, so it can wait longer for a free sandbox than a user's Run does. */
    private static final Duration SLOT_WAIT = Duration.ofMinutes(2);

    private final ScenarioExecutionService execution;

    public HarnessValidator(ScenarioExecutionService execution) {
        this.execution = execution;
    }

    /** Why a generated harness was rejected, in words the model can act on in its repair attempt. */
    public static class HarnessRejected extends RuntimeException {
        public HarnessRejected(String problem) {
            super(problem);
        }
    }

    /** The sandbox itself is unavailable: not the scenario's fault, so it's never sent back as a repair. */
    public static class SandboxUnavailable extends RuntimeException {
        public SandboxUnavailable(String message) {
            super(message);
        }
    }

    public ScenarioValidation validate(GeneratedScenario g) {
        List<WorkspaceFile> starter = g.workspace().files().stream().map(f -> new WorkspaceFile(f.path(), f.content())).toList();
        RunResult before = run(g, starter);
        switch (before.status()) {
            case PASSED -> throw new HarnessRejected("The starter workspace already passes every check, so the checks don't detect the "
                    + "problem. Make at least one check fail on the starter code and pass only once the problem is fixed.");
            case COMPILE_ERROR, RUNTIME_ERROR -> throw new HarnessRejected("The starter workspace must load and run, but it fails with: "
                    + before.message() + detail(before));
            case TIMEOUT, LIMIT_EXCEEDED -> throw new HarnessRejected("The starter run was stopped: " + before.message()
                    + " Keep checks fast and bounded.");
            case FAILED -> { /* expected: the problem is reproduced */ }
        }
        List<String> ran = before.checks().stream().map(CheckResult::name).toList();
        if (!ran.equals(g.checks().checkNames())) {
            throw new HarnessRejected("checkNames must list exactly the checks registered, in order. Declared " + g.checks().checkNames()
                    + " but the checks file registered " + ran + ".");
        }

        RunResult after = run(g, withSolution(g));
        if (after.status() != RunStatus.PASSED) {
            String why = after.status() == RunStatus.FAILED
                    ? after.checks().stream().filter(c -> !c.passed()).limit(3).map(c -> "'" + c.name() + "': " + c.message())
                            .reduce((a, b) -> a + "; " + b).orElse("")
                    : after.status() + ": " + after.message() + detail(after);
            throw new HarnessRejected("The reference solution must pass every check, but it doesn't. " + why);
        }
        return new ScenarioValidation(outcome(before), outcome(after), Instant.now().toString());
    }

    private RunResult run(GeneratedScenario g, List<WorkspaceFile> files) {
        try {
            return execution.run(g.workspace().language(), files, g.checks().source(), SLOT_WAIT);
        } catch (ApiException e) {
            if (e.getCode().equals("INVALID_WORKSPACE")) {
                throw new HarnessRejected("Invalid workspace: " + e.getMessage());
            }
            throw new SandboxUnavailable(e.getMessage()); // unavailable or busy: not the scenario's fault
        }
    }

    /** The starter with the reference's files swapped in. */
    static List<WorkspaceFile> withSolution(GeneratedScenario g) {
        Map<String, String> files = new LinkedHashMap<>();
        g.workspace().files().forEach(f -> files.put(f.path(), f.content()));
        g.referenceSolution().files().forEach(f -> files.put(f.path(), f.content()));
        return files.entrySet().stream().map(e -> new WorkspaceFile(e.getKey(), e.getValue())).toList();
    }

    private static String detail(RunResult r) {
        String stderr = r.stderr() == null ? "" : r.stderr().strip();
        return stderr.isEmpty() ? "" : " Output: " + (stderr.length() <= 800 ? stderr : stderr.substring(0, 800));
    }

    private static ScenarioValidation.Outcome outcome(RunResult r) {
        return new ScenarioValidation.Outcome(r.status().name(), r.passed(), r.total(), r.durationMs());
    }
}
