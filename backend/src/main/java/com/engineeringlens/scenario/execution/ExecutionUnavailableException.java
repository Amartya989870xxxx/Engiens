package com.engineeringlens.scenario.execution;

/** The sandbox couldn't run at all (Docker missing or stopped, image not pulled). Never caused by user code. */
public class ExecutionUnavailableException extends RuntimeException {

    public ExecutionUnavailableException(String message) {
        super(message);
    }

    public ExecutionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
