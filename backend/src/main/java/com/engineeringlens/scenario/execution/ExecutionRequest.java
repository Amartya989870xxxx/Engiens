package com.engineeringlens.scenario.execution;

import java.time.Duration;
import java.util.Map;

/**
 * One isolated execution.
 *
 * @param image        the sandbox image (a pinned language runtime)
 * @param command      shell command run in /work after the files are unpacked there
 * @param files        relative path → text; the only files the sandbox can see
 * @param timeout      wall-clock limit for the whole execution, container start included
 * @param memoryMb     memory limit (no swap)
 * @param resultMarker lines starting with this are structured results, collected separately from output
 */
public record ExecutionRequest(String image, String command, Map<String, String> files, Duration timeout, int memoryMb,
        String resultMarker) {

    @Override
    public String toString() {
        return "ExecutionRequest[image=" + image + ", files=" + files.size() + ", timeout=" + timeout + ", memoryMb=" + memoryMb + "]";
    }
}
