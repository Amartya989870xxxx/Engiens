package com.engineeringlens.scenario.execution;

/**
 * Runs a command over a set of files in an isolated, throwaway environment and reports what happened.
 * The only implementation today starts a locked-down Docker container; a dedicated sandbox worker service
 * could replace it later without changing anything above this interface.
 */
public interface ExecutionProvider {

    /** True when executions can run right now (for example, the Docker daemon is reachable). */
    boolean available();

    /** @throws ExecutionUnavailableException when the sandbox itself can't run (not when the user's code fails) */
    ExecutionResult execute(ExecutionRequest request);
}
