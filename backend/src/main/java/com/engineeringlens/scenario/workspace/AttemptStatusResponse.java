package com.engineeringlens.scenario.workspace;

import java.time.Instant;
import java.util.UUID;

import com.engineeringlens.scenario.EvaluationStatus;
import com.engineeringlens.scenario.WorkMode;
import com.engineeringlens.scenario.execution.RunResult;

/** A submission's state while the lab is open: what was submitted and whether its evaluation is done. */
public record AttemptStatusResponse(UUID id, UUID scenarioId, WorkMode mode, RunResult runResult, EvaluationStatus evaluationStatus,
        String errorCode, String errorMessage, Instant createdAt) {
}
