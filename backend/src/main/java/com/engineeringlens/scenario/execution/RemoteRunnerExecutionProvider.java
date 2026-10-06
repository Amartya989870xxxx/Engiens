package com.engineeringlens.scenario.execution;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Runs scenario code on the Engiens runner service (runner/engiens_runner.py) instead of a local Docker sandbox, for
 * hosts that can't start containers (Railway). It sends exactly the request the Docker sandbox would get (command,
 * files, time limit, result marker) over the private network, and gets back the same fields, so everything above
 * {@link ExecutionProvider} works unchanged. Selected with {@code scenario.execution.provider=runner}.
 */
@Component
@ConditionalOnProperty(prefix = "scenario.execution", name = "provider", havingValue = "runner")
public class RemoteRunnerExecutionProvider implements ExecutionProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteRunnerExecutionProvider.class);
    private static final long AVAILABILITY_TTL_MS = 30_000;
    /** Time beyond the run's own limit for the request itself (connecting, writing files, cleanup). */
    private static final Duration REQUEST_MARGIN = Duration.ofSeconds(20);

    private final ExecutionProperties properties;
    private final String baseUrl;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile long checkedAt;
    private volatile boolean lastAvailable;

    public RemoteRunnerExecutionProvider(ExecutionProperties properties, @Value("${scenario.execution.runner-url:}") String baseUrl,
            @Value("${scenario.execution.runner-token:}") String token, ObjectMapper json) {
        this.properties = properties;
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
        this.token = token;
        this.json = json;
        if (this.baseUrl.isEmpty() || token.isBlank()) {
            log.warn("Code runner not configured: set SCENARIO_RUNNER_URL and SCENARIO_RUNNER_TOKEN");
        }
    }

    @Override
    public String description() {
        return "runner service at " + baseUrl;
    }

    @Override
    public boolean available() {
        if (!properties.enabled() || baseUrl.isEmpty() || token.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt > AVAILABILITY_TTL_MS) {
            lastAvailable = healthy();
            checkedAt = now;
        }
        return lastAvailable;
    }

    private boolean healthy() {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health")).timeout(Duration.ofSeconds(5))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The runner's answer; mirrors {@link ExecutionResult}. */
    record RunnerResult(int exitCode, boolean timedOut, String stdout, String stderr, boolean outputTruncated, List<String> resultLines,
            long durationMs) {
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        if (baseUrl.isEmpty() || token.isBlank()) {
            throw new ExecutionUnavailableException("The code runner isn't configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", request.command());
        body.put("files", request.files());
        body.put("timeoutMs", request.timeout().toMillis());
        body.put("memoryMb", request.memoryMb());
        body.put("outputLimitBytes", properties.outputLimitBytes());
        body.put("resultMarker", request.resultMarker());
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/run"))
                .timeout(request.timeout().plus(REQUEST_MARGIN))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            lastAvailable = false;
            checkedAt = System.currentTimeMillis();
            throw new ExecutionUnavailableException("The code runner couldn't be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionUnavailableException("Execution was interrupted", e);
        }
        if (response.statusCode() != 200) {
            // 429 busy, 401 wrong token, 400 a request it refused, 5xx a runner problem: never the user's code.
            log.warn("Code runner refused a run: status={} image={}", response.statusCode(), request.image());
            throw new ExecutionUnavailableException("The code runner couldn't run this (HTTP " + response.statusCode() + ")");
        }
        RunnerResult r;
        try {
            r = json.readValue(response.body(), RunnerResult.class);
        } catch (RuntimeException e) {
            throw new ExecutionUnavailableException("The code runner sent an unreadable answer", e);
        }
        // Same reading as the Docker sandbox: 126/127 mean the language's command couldn't start (a missing runtime).
        if (!r.timedOut() && (r.exitCode() == 126 || r.exitCode() == 127)) {
            log.warn("Code runner could not start the command: exit={} image={}", r.exitCode(), request.image());
            throw new ExecutionUnavailableException("The code runner couldn't start the language runtime");
        }
        log.info("Runner finished: image={} exit={} timedOut={} durationMs={} results={}", request.image(), r.exitCode(), r.timedOut(),
                r.durationMs(), r.resultLines() == null ? 0 : r.resultLines().size());
        return new ExecutionResult(r.exitCode(), r.timedOut(), nonNull(r.stdout()), nonNull(r.stderr()), r.outputTruncated(),
                r.resultLines() == null ? List.of() : List.copyOf(r.resultLines()), r.durationMs());
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
