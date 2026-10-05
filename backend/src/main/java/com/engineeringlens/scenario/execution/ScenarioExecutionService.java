package com.engineeringlens.scenario.execution;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.ScenarioLanguage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a workspace against a scenario's hidden checks in the sandbox and turns what happened into an
 * objective {@link RunResult}: which named checks passed, or why nothing could be checked (compile error,
 * crash, timeout, memory). It never grades; that's the AI evaluation's job, with this result as evidence.
 */
@Service
public class ScenarioExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioExecutionService.class);

    public static final int MAX_FILES = 40;
    public static final int MAX_FILE_BYTES = 64 * 1024;
    public static final int MAX_WORKSPACE_BYTES = 256 * 1024;
    private static final int MAX_CHECKS = 50;
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*(/[A-Za-z0-9_][A-Za-z0-9_.\\-]*)*");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExecutionProvider provider;
    private final ExecutionProperties properties;
    private final Semaphore slots;

    public ScenarioExecutionService(ExecutionProvider provider, ExecutionProperties properties) {
        this.provider = provider;
        this.properties = properties;
        this.slots = new Semaphore(Math.max(1, properties.maxConcurrent()), true);
    }

    public boolean available() {
        return provider.available();
    }

    /** A user's Run: waits briefly for a free sandbox, then reports "busy" rather than queueing forever. */
    public RunResult run(ScenarioLanguage language, List<WorkspaceFile> workspace, String checksSource) {
        return run(language, workspace, checksSource, properties.slotWait());
    }

    public RunResult run(ScenarioLanguage language, List<WorkspaceFile> workspace, String checksSource, Duration waitForSlot) {
        validateWorkspace(workspace);
        if (!provider.available()) {
            throw unavailable();
        }
        LanguageRuntime runtime = LanguageRuntime.of(language);
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String nonce = HexFormat.of().formatHex(random);
        Map<String, String> files = new LinkedHashMap<>();
        workspace.forEach(f -> files.put(f.path(), f.content()));
        files.putAll(runtime.runnerFiles());
        files.put(runtime.checksFile(), checksSource);
        files.put(".engiens_nonce", nonce);

        boolean acquired;
        try {
            acquired = slots.tryAcquire(waitForSlot.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SCENARIO_EXECUTION_BUSY",
                    "The code sandbox is busy right now. Try running again in a moment.");
        }
        ExecutionResult result;
        try {
            result = provider.execute(new ExecutionRequest(runtime.image(), runtime.command(), files, runtime.timeout(),
                    runtime.memoryMb(), "@@ENGIENS:" + nonce + ":"));
        } catch (ExecutionUnavailableException e) {
            log.warn("Sandbox unavailable: {}", e.getMessage());
            throw unavailable();
        } finally {
            slots.release();
        }
        return interpret(runtime, result);
    }

    /** Turns raw execution output into the objective result. Package-visible for tests. */
    static RunResult interpret(LanguageRuntime runtime, ExecutionResult r) {
        List<CheckResult> checks = new ArrayList<>();
        String outcome = null;
        String summaryMessage = null;
        for (String line : r.resultLines()) {
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (RuntimeException e) {
                continue; // not a result line we wrote
            }
            String kind = node.path("kind").asString("");
            if (kind.equals("check") && checks.size() < MAX_CHECKS) {
                checks.add(new CheckResult(cap(node.path("name").asString("check"), 200), node.path("passed").asBoolean(false),
                        node.path("message").isNull() ? null : cap(node.path("message").asString(""), 1000), node.path("ms").asLong(0)));
            } else if (kind.equals("summary")) {
                outcome = node.path("outcome").asString("");
                summaryMessage = node.path("message").isNull() ? null : cap(node.path("message").asString(""), 1000);
            }
        }
        String stderr = r.stderr();
        int passed = (int) checks.stream().filter(CheckResult::passed).count();
        int total = checks.size();
        RunStatus status;
        String message = null;
        if (r.timedOut()) {
            status = RunStatus.TIMEOUT;
            message = "The run took longer than " + runtime.timeout().toSeconds() + " seconds in total and was stopped.";
        } else if (r.exitCode() == 137) {
            status = RunStatus.LIMIT_EXCEEDED;
            message = "The run used more than " + runtime.memoryMb() + " MB of memory and was stopped.";
        } else if ("compile_error".equals(outcome)) {
            status = RunStatus.COMPILE_ERROR;
            if (runtime == LanguageRuntime.JAVA) {
                stderr = CompilerOutput.redact(stderr);
                message = CompilerOutput.firstError(stderr);
            } else {
                message = summaryMessage;
            }
        } else if ("load_error".equals(outcome)) {
            status = RunStatus.RUNTIME_ERROR;
            message = "Your code failed while loading: " + summaryMessage;
        } else if (!"ran".equals(outcome)) {
            status = RunStatus.RUNTIME_ERROR;
            message = "The run stopped before all checks finished (exit code " + r.exitCode() + "). Does your code exit the process?";
        } else if (total == 0) {
            status = RunStatus.RUNTIME_ERROR;
            message = "No checks ran.";
        } else {
            status = passed == total ? RunStatus.PASSED : RunStatus.FAILED;
        }
        return new RunResult(status, passed, total, r.durationMs(), List.copyOf(checks), message, r.stdout(), stderr, r.outputTruncated());
    }

    /** User-editable workspaces are validated before anything runs; Engiens file names are reserved. */
    public static void validateWorkspace(List<WorkspaceFile> workspace) {
        if (workspace == null || workspace.isEmpty() || workspace.size() > MAX_FILES) {
            throw invalid("A workspace has between 1 and " + MAX_FILES + " files.");
        }
        long total = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (WorkspaceFile f : workspace) {
            String path = f == null ? null : f.path();
            if (path == null || path.length() > 200 || !SAFE_PATH.matcher(path).matches() || path.contains("..")) {
                throw invalid("File paths must be relative and use letters, digits, '.', '_', '-' and '/'.");
            }
            if (path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT).startsWith("engiens")) {
                throw invalid("File names starting with \"engiens\" are reserved.");
            }
            if (!seen.add(path)) {
                throw invalid("Each file path can appear only once.");
            }
            int bytes = f.content() == null ? 0 : f.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (f.content() == null || bytes > MAX_FILE_BYTES) {
                throw invalid("Each file can be at most " + MAX_FILE_BYTES / 1024 + " KB.");
            }
            total += bytes;
        }
        if (total > MAX_WORKSPACE_BYTES) {
            throw invalid("The workspace can be at most " + MAX_WORKSPACE_BYTES / 1024 + " KB in total.");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void reportSandboxState() {
        if (!provider.available()) {
            log.warn("Code sandbox unavailable (Docker not reachable or scenario.execution.enabled=false): scenarios will be approach-only");
        } else if (provider instanceof DockerSandboxExecutionProvider docker) {
            List<String> missing = docker.missingImages(LanguageRuntime.images());
            if (!missing.isEmpty()) {
                log.warn("Sandbox images not pulled yet: {}. Run deploy/sandbox-images.sh (make sandbox-images).", missing);
            } else {
                log.info("Code sandbox ready: Docker reachable, {} pinned image(s) present", LanguageRuntime.images().size());
            }
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE", message);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SCENARIO_EXECUTION_UNAVAILABLE",
                "Code execution isn't available right now. You can still switch to Approach and explain your solution.");
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
