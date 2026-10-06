package com.engineeringlens.scenario.execution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sandbox configuration.
 *
 * @param enabled          false turns code execution off (scenarios then fall back to approach mode)
 * @param dockerCommand    the Docker CLI to call
 * @param maxConcurrent    sandboxes running at once, across all users
 * @param slotWait         how long a Run waits for a free sandbox before reporting "busy"
 * @param outputLimitBytes cap per stream (stdout, stderr) kept for display
 * @param provider         where code runs: "docker" (a local Docker sandbox, the default) or "runner" (the remote
 *                         Engiens runner service, for hosts that can't start containers, e.g. Railway)
 */
@ConfigurationProperties("scenario.execution")
public record ExecutionProperties(@DefaultValue("true") boolean enabled, @DefaultValue("docker") String dockerCommand,
        @DefaultValue("2") int maxConcurrent, @DefaultValue("PT15S") Duration slotWait, @DefaultValue("16384") int outputLimitBytes,
        @DefaultValue("docker") String provider) {
}
