package com.engineeringlens.scenario.execution;

/**
 * Runs a command over a set of files in an isolated, throwaway environment and reports what happened.
 * Everywhere the application runs (locally and in production on EC2) this is {@link DockerSandboxExecutionProvider}:
 * a locked-down, throwaway Docker container per run. The interface is the seam that keeps {@link ScenarioExecutionService}
 * free of Docker details and lets tests replace real containers with a scripted fake.
 */
public interface ExecutionProvider {

    /** True when executions can run right now (for example, the Docker daemon is reachable). */
    boolean available();

    /** Where code runs, for logs. */
    default String description() {
        return getClass().getSimpleName();
    }

    /** @throws ExecutionUnavailableException when the sandbox itself can't run (not when the user's code fails) */
    ExecutionResult execute(ExecutionRequest request);
}
