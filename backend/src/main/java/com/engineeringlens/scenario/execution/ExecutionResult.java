package com.engineeringlens.scenario.execution;

import java.util.List;

/**
 * What an execution did. Output is capped; result lines are the marked lines with the marker removed.
 *
 * @param exitCode        the command's exit code (137 usually means the memory limit killed it)
 * @param timedOut        the wall-clock limit was hit and the sandbox was killed
 * @param outputTruncated stdout or stderr was longer than the cap
 */
public record ExecutionResult(int exitCode, boolean timedOut, String stdout, String stderr, boolean outputTruncated,
        List<String> resultLines, long durationMs) {
}
