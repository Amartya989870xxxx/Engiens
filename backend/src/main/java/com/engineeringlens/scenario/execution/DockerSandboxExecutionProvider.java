package com.engineeringlens.scenario.execution;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs each execution in a fresh, locked-down Docker container. User code never runs inside the backend's
 * JVM: this class only starts the `docker` CLI, streams the files in, enforces the wall clock and collects
 * capped output.
 *
 * <p>Isolation per container: no network; read-only root filesystem with small in-memory /work and /tmp;
 * an unprivileged user; every Linux capability dropped and privilege escalation blocked; limits on memory
 * (no swap), CPU, processes and open files. Nothing from the host is mounted and no environment variable
 * is passed in, so the code can't see source trees, .env values, tokens or database credentials.
 */
@Component
@ConditionalOnProperty(prefix = "scenario.execution", name = "provider", havingValue = "docker", matchIfMissing = true)
public class DockerSandboxExecutionProvider implements ExecutionProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxExecutionProvider.class);
    private static final long AVAILABILITY_TTL_MS = 30_000;

    private final ExecutionProperties properties;
    private volatile boolean lastAvailable;
    private volatile long checkedAt;

    public DockerSandboxExecutionProvider(ExecutionProperties properties) {
        this.properties = properties;
    }

    @Override
    public String description() {
        return "local Docker sandbox";
    }

    @Override
    public boolean available() {
        if (!properties.enabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt > AVAILABILITY_TTL_MS) {
            lastAvailable = run(List.of(properties.dockerCommand(), "info", "--format", "{{.ServerVersion}}"), 10) == 0;
            checkedAt = now;
        }
        return lastAvailable;
    }

    /** Images not pulled yet (the sandbox never pulls on its own: a pull inside a user's Run would time out). */
    public List<String> missingImages(List<String> images) {
        return images.stream().filter(i -> run(List.of(properties.dockerCommand(), "image", "inspect", i), 10) != 0).toList();
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        String name = "engiens-run-" + UUID.randomUUID();
        List<String> command = command(name, request);
        long start = System.nanoTime();
        Process process;
        try {
            process = docker(command).start();
        } catch (IOException e) {
            throw new ExecutionUnavailableException("The Docker CLI couldn't be started", e);
        }
        OutputCollector stdout = new OutputCollector(process.getInputStream(), properties.outputLimitBytes(), request.resultMarker());
        OutputCollector stderr = new OutputCollector(process.getErrorStream(), properties.outputLimitBytes(), null);
        Thread out = Thread.ofVirtual().start(stdout);
        Thread err = Thread.ofVirtual().start(stderr);
        byte[] archive = TarArchive.of(request.files());
        // Written on its own thread: if the container dies early, a blocked write must not hang this request.
        Thread in = Thread.ofVirtual().start(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(archive);
            } catch (IOException e) {
                // the container exited before reading everything; its exit code tells the story
            }
        });

        boolean timedOut = false;
        try {
            if (!process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                run(List.of(properties.dockerCommand(), "kill", name), 10); // --rm then removes it
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            out.join(5_000);
            err.join(5_000);
            in.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run(List.of(properties.dockerCommand(), "rm", "-f", name), 10);
            throw new ExecutionUnavailableException("Execution was interrupted", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        int exit = timedOut ? -1 : process.exitValue();
        String stderrText = stderr.display();
        // 125: `docker run` itself failed (daemon down, image missing); 126/127: the command couldn't start.
        if (exit == 125 || exit == 126 || exit == 127) {
            log.warn("Sandbox could not run: exit={} image={} detail={}", exit, request.image(), firstLine(stderrText));
            throw new ExecutionUnavailableException("The sandbox couldn't start (" + firstLine(stderrText) + ")");
        }
        log.info("Sandbox finished: image={} exit={} timedOut={} durationMs={} results={}", request.image(), exit, timedOut, durationMs,
                stdout.results().size());
        return new ExecutionResult(exit, timedOut, stdout.display(), stderrText, stdout.truncated() || stderr.truncated(),
                stdout.results(), durationMs);
    }

    List<String> command(String name, ExecutionRequest request) {
        return new ArrayList<>(List.of(properties.dockerCommand(), "run", "--rm", "-i", "--name", name,
                "--pull", "never",                                   // never download during a run
                "--network", "none",                                 // no internet, no host, no database
                "--read-only",                                       // the image itself can't be modified
                "--tmpfs", "/work:rw,exec,nosuid,size=64m,mode=1777", // the only writable places, in memory
                "--tmpfs", "/tmp:rw,exec,nosuid,size=64m,mode=1777",
                "--user", "65534:65534",                             // "nobody", not root
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", "128",                               // no fork bombs
                "--memory", request.memoryMb() + "m",
                "--memory-swap", request.memoryMb() + "m",           // equal to memory: no swap
                "--cpus", "1",
                "--ulimit", "nofile=256:256",
                "--workdir", "/work",
                "--env", "HOME=/tmp",
                "--env", "TMPDIR=/tmp",
                "--entrypoint", "/bin/sh",
                request.image(),
                "-c", "tar -xmf - -C /work && " + request.command()));
    }

    /**
     * The Docker CLI gets an emptied environment (only what it needs to find the daemon), so nothing from the
     * backend's environment, such as AI keys or the JWT secret, is even available to the CLI process.
     */
    private ProcessBuilder docker(List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> env = pb.environment();
        Map<String, String> keep = new java.util.HashMap<>();
        for (String key : List.of("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_CERT_PATH",
                "DOCKER_TLS_VERIFY", "XDG_RUNTIME_DIR")) {
            if (env.containsKey(key)) {
                keep.put(key, env.get(key));
            }
        }
        env.clear();
        env.putAll(keep);
        return pb;
    }

    /** Runs a short Docker CLI command, returning its exit code (-1 if it couldn't run or took too long). */
    private int run(List<String> command, int timeoutSeconds) {
        try {
            Process p = docker(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String firstLine(String text) {
        String line = text == null ? "" : text.lines().findFirst().orElse("");
        return line.length() <= 200 ? line : line.substring(0, 200);
    }
}
