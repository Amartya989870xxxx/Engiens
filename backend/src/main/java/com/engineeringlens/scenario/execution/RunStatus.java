package com.engineeringlens.scenario.execution;

/** The objective outcome of a Run. FAILED means it ran and at least one check failed. */
public enum RunStatus {
    PASSED, FAILED, COMPILE_ERROR, RUNTIME_ERROR, TIMEOUT, LIMIT_EXCEEDED
}
