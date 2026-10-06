package com.engineeringlens.scenario.execution;

/**
 * Runs a command over a set of files in an isolated, throwaway environment and reports what happened.
 * Two implementations, chosen by {@code scenario.execution.provider}: a locked-down local Docker container
 * ({@link DockerSandboxExecutionProvider}, the default) or the Engiens runner service over the network
 * ({@link RemoteRunnerExecutionProvider}, for hosts that can't start containers). Nothing above this interface changes.
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
