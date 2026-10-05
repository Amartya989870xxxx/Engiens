package com.engineeringlens.scenario.execution;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.engineeringlens.scenario.ScenarioLanguage;

/**
 * How each supported language runs in the sandbox: a pinned image, limits, the Engiens-owned runner files
 * (from resources/scenario-runtime) and the file name the hidden checks must use. The AI writes checks
 * against these runners' tiny APIs; it never writes the runner itself.
 */
public enum LanguageRuntime {

    PYTHON(ScenarioLanguage.PYTHON, Images.PYTHON, 256, Duration.ofSeconds(30), "engiens_checks.py",
            List.of("python/engiens.py", "python/engiens_run.py"),
            "exec python3 -B -s -u engiens_run.py"),

    JAVASCRIPT(ScenarioLanguage.JAVASCRIPT, Images.NODE, 256, Duration.ofSeconds(30), "engiens_checks.mjs",
            List.of("javascript/engiens.mjs", "javascript/engiens_run.mjs"),
            "exec node --disable-warning=ExperimentalWarning --max-old-space-size=160 engiens_run.mjs engiens_checks.mjs"),

    // Node 24 strips TypeScript types natively: erasable syntax only (no enums or namespaces), imports end in ".ts".
    TYPESCRIPT(ScenarioLanguage.TYPESCRIPT, Images.NODE, 256, Duration.ofSeconds(30), "engiens_checks.ts",
            List.of("javascript/engiens.mjs", "javascript/engiens_run.mjs"),
            "exec node --disable-warning=ExperimentalWarning --max-old-space-size=160 engiens_run.mjs engiens_checks.ts"),

    // Compile everything first; on failure, print the compiler's output and a compile_error result, then stop.
    JAVA(ScenarioLanguage.JAVA, Images.JAVA, 512, Duration.ofSeconds(45), "EngiensChecks.java",
            List.of("java/Engiens.java", "java/EngiensRunner.java"),
            "N=$(cat .engiens_nonce); find . -name '*.java' > /tmp/sources.txt; "
                    + "if ! javac -J-XX:+UseSerialGC -J-Xmx256m -J-XX:TieredStopAtLevel=1 -J-XX:-UsePerfData -encoding UTF-8 -nowarn "
                    + "-d /tmp/classes @/tmp/sources.txt 2>/tmp/javac.txt; then head -c 16000 /tmp/javac.txt >&2; "
                    + "printf '\\n@@ENGIENS:%s:{\"kind\":\"summary\",\"outcome\":\"compile_error\",\"message\":\"Compilation failed\"}\\n' \"$N\"; "
                    + "exit 0; fi; unset N; "
                    + "exec java -XX:+UseSerialGC -Xmx256m -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xss1m -cp /tmp/classes EngiensRunner");

    /**
     * Pinned by digest (the multi-architecture index, so the same reference runs on x86 and ARM64 servers): a run never
     * depends on what a tag points to today. Update together with deploy/sandbox-images.sh (a test checks they match).
     */
    static final class Images {
        static final String PYTHON = "python:3.12-slim@sha256:02108f5d322dd89f1c9e552442c25acb0543dfdbc455693a5599624f20d9155d";
        static final String NODE = "node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1";
        static final String JAVA = "eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159";

        private Images() {
        }
    }

    private final ScenarioLanguage language;
    private final String image;
    private final int memoryMb;
    private final Duration timeout;
    private final String checksFile;
    private final Map<String, String> runnerFiles;
    private final String command;

    LanguageRuntime(ScenarioLanguage language, String image, int memoryMb, Duration timeout, String checksFile, List<String> resources,
            String command) {
        this.language = language;
        this.image = image;
        this.memoryMb = memoryMb;
        this.timeout = timeout;
        this.checksFile = checksFile;
        this.command = command;
        Map<String, String> files = new LinkedHashMap<>();
        for (String resource : resources) {
            files.put(resource.substring(resource.indexOf('/') + 1), read("/scenario-runtime/" + resource));
        }
        this.runnerFiles = Map.copyOf(files);
    }

    public static LanguageRuntime of(ScenarioLanguage language) {
        for (LanguageRuntime r : values()) {
            if (r.language == language) {
                return r;
            }
        }
        throw new IllegalArgumentException("No sandbox runtime for " + language);
    }

    public static List<String> images() {
        return java.util.Arrays.stream(values()).map(LanguageRuntime::image).distinct().toList();
    }

    public ScenarioLanguage language() { return language; }
    public String image() { return image; }
    public int memoryMb() { return memoryMb; }
    public Duration timeout() { return timeout; }
    public String checksFile() { return checksFile; }
    public Map<String, String> runnerFiles() { return runnerFiles; }
    public String command() { return command; }

    private static String read(String resource) {
        try (InputStream in = LanguageRuntime.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing sandbox runtime file " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
