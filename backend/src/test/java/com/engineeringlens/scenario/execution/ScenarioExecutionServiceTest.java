package com.engineeringlens.scenario.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.ScenarioLanguage;

/** How execution output becomes an objective result, and the rules around it, with a fake sandbox. */
class ScenarioExecutionServiceTest {

    private static final List<WorkspaceFile> WORKSPACE = List.of(new WorkspaceFile("orders.py", "def place(o):\n    return o\n"));
    private static final ExecutionProperties PROPERTIES = new ExecutionProperties(true, "docker", 1, Duration.ofMillis(200), 16384, "docker");

    private static String check(String name, boolean passed, String message) {
        return "{\"kind\":\"check\",\"name\":\"" + name + "\",\"passed\":" + passed + ",\"message\":"
                + (message == null ? "null" : "\"" + message + "\"") + ",\"ms\":3}";
    }

    private static final String RAN = "{\"kind\":\"summary\",\"outcome\":\"ran\"}";

    private static ExecutionResult result(int exit, boolean timedOut, String stderr, String... lines) {
        return new ExecutionResult(exit, timedOut, "", stderr, false, List.of(lines), 900);
    }

    /** A sandbox that returns a fixed result and remembers the request. */
    private static class FakeProvider implements ExecutionProvider {
        boolean up = true;
        ExecutionResult next = result(0, false, "", RAN);
        final AtomicReference<ExecutionRequest> last = new AtomicReference<>();

        @Override
        public boolean available() {
            return up;
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request) {
            last.set(request);
            return next;
        }
    }

    @Test
    void countsPassedAndFailedChecks() {
        RunResult r = ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(0, false, "",
                check("places an order", true, null), check("ignores duplicates", false, "a second order was created"), RAN));

        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.passed()).isEqualTo(1);
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.checks().get(1).message()).isEqualTo("a second order was created");
        assertThat(ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(0, false, "", check("a", true, null), RAN)).status())
                .isEqualTo(RunStatus.PASSED);
    }

    @Test
    void reportsWhyNothingCouldBeChecked() {
        assertThat(ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(-1, true, "", check("a", true, null))))
                .extracting(RunResult::status, RunResult::passed).containsExactly(RunStatus.TIMEOUT, 1);
        assertThat(ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(137, false, "")).status())
                .isEqualTo(RunStatus.LIMIT_EXCEEDED);
        RunResult compile = ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(0, false, "",
                "{\"kind\":\"summary\",\"outcome\":\"compile_error\",\"message\":\"orders.py line 2: invalid syntax\"}"));
        assertThat(compile.status()).isEqualTo(RunStatus.COMPILE_ERROR);
        assertThat(compile.message()).isEqualTo("orders.py line 2: invalid syntax");
        RunResult load = ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(0, false, "",
                "{\"kind\":\"summary\",\"outcome\":\"load_error\",\"message\":\"ImportError: cannot import name 'place'\"}"));
        assertThat(load.status()).isEqualTo(RunStatus.RUNTIME_ERROR);
        assertThat(load.message()).contains("cannot import name 'place'");
        RunResult exited = ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(3, false, "", check("a", true, null)));
        assertThat(exited.status()).isEqualTo(RunStatus.RUNTIME_ERROR); // user code ended the process before the summary
        assertThat(exited.message()).contains("exit code 3");
        assertThat(ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(0, false, "", RAN)).status())
                .isEqualTo(RunStatus.RUNTIME_ERROR); // a harness without checks never counts as passing
    }

    @Test
    void javaCompileErrorsNeverQuoteTheHiddenChecks() {
        String javac = """
                ./com/shop/OrderService.java:9: error: ';' expected
                        orders.add(id)
                                      ^
                ./EngiensChecks.java:7: error: method place in class OrderService cannot be applied to given types;
                            s.place("SECRET_CHECK_LINE");
                             ^
                  required: String,int
                  found:    String
                2 errors""";
        RunResult r = ScenarioExecutionService.interpret(LanguageRuntime.JAVA, result(0, false, javac,
                "{\"kind\":\"summary\",\"outcome\":\"compile_error\",\"message\":\"Compilation failed\"}"));

        assertThat(r.status()).isEqualTo(RunStatus.COMPILE_ERROR);
        assertThat(r.stderr()).doesNotContain("SECRET_CHECK_LINE").doesNotContain("EngiensChecks.java")
                .contains("com/shop/OrderService.java:9: error: ';' expected", "orders.add(id)",
                        "hidden checks: error: method place in class OrderService cannot be applied", "required: String,int");
        assertThat(r.message()).isEqualTo("com/shop/OrderService.java:9: error: ';' expected");
    }

    @Test
    void ignoresMalformedResultLinesAndCapsText() {
        String longName = "n".repeat(500);
        RunResult r = ScenarioExecutionService.interpret(LanguageRuntime.PYTHON, result(0, false, "", "not json",
                check(longName, false, "m".repeat(5000)), RAN));
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.checks().get(0).name()).hasSize(200);
        assertThat(r.checks().get(0).message()).hasSize(1000);
    }

    @Test
    void sendsTheWorkspaceRunnerChecksAndAFreshMarkerButNothingElse() {
        FakeProvider provider = new FakeProvider();
        new ScenarioExecutionService(provider, PROPERTIES).run(ScenarioLanguage.PYTHON, WORKSPACE, "from engiens import check\n");
        ExecutionRequest first = provider.last.get();
        assertThat(first.files()).containsOnlyKeys("orders.py", "engiens.py", "engiens_run.py", "engiens_checks.py", ".engiens_nonce");
        assertThat(first.image()).isEqualTo(LanguageRuntime.PYTHON.image());
        assertThat(first.resultMarker()).isEqualTo("@@ENGIENS:" + first.files().get(".engiens_nonce") + ":");

        new ScenarioExecutionService(provider, PROPERTIES).run(ScenarioLanguage.PYTHON, WORKSPACE, "");
        assertThat(provider.last.get().resultMarker()).isNotEqualTo(first.resultMarker()); // unguessable per run
    }

    @Test
    void rejectsUnsafeWorkspaces() {
        ScenarioExecutionService service = new ScenarioExecutionService(new FakeProvider(), PROPERTIES);
        for (String path : List.of("../etc/passwd", "/abs.py", "a/../../b.py", "engiens_checks.py", "lib/Engiens.java", ".env",
                "a//b.py", "spaces in.py", "x/")) {
            assertThatThrownBy(() -> service.run(ScenarioLanguage.PYTHON, List.of(new WorkspaceFile(path, "x")), ""))
                    .as(path).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_WORKSPACE"));
        }
        assertThatThrownBy(() -> service.run(ScenarioLanguage.PYTHON, List.of(new WorkspaceFile("big.py", "x".repeat(70_000))), ""))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.run(ScenarioLanguage.PYTHON, List.of(), "")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.run(ScenarioLanguage.PYTHON,
                List.of(new WorkspaceFile("a.py", "1"), new WorkspaceFile("a.py", "2")), "")).isInstanceOf(ApiException.class);
        service.run(ScenarioLanguage.PYTHON, List.of(new WorkspaceFile("app/services/orders_v2.py", "x = 1")), ""); // fine
    }

    @Test
    void reportsUnavailableWhenTheSandboxIsDown() {
        FakeProvider down = new FakeProvider();
        down.up = false;
        assertThatThrownBy(() -> new ScenarioExecutionService(down, PROPERTIES).run(ScenarioLanguage.PYTHON, WORKSPACE, ""))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SCENARIO_EXECUTION_UNAVAILABLE"));

        ExecutionProvider broken = new FakeProvider() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                throw new ExecutionUnavailableException("image missing");
            }
        };
        assertThatThrownBy(() -> new ScenarioExecutionService(broken, PROPERTIES).run(ScenarioLanguage.PYTHON, WORKSPACE, ""))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SCENARIO_EXECUTION_UNAVAILABLE"));
    }

    @Test
    void reportsBusyInsteadOfQueueingForeverWhenAllSandboxesAreInUse() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutionProvider slow = new FakeProvider() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return result(0, false, "", check("a", true, null), RAN);
            }
        };
        ScenarioExecutionService service = new ScenarioExecutionService(slow, PROPERTIES); // one sandbox at a time
        Thread first = Thread.ofVirtual().start(() -> service.run(ScenarioLanguage.PYTHON, WORKSPACE, ""));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.run(ScenarioLanguage.PYTHON, WORKSPACE, ""))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SCENARIO_EXECUTION_BUSY"));
        release.countDown();
        first.join();
        assertThat(service.run(ScenarioLanguage.PYTHON, WORKSPACE, "").status()).isEqualTo(RunStatus.PASSED); // the slot was released
    }

    @Test
    void outputIsCappedAndResultLinesAreSeparatedFromIt() {
        String marker = "@@ENGIENS:abc:";
        String printed = "hello\n" + "x".repeat(100_000) + "\n\n" + marker + check("a", true, null) + "\n"
                + "@@ENGIENS:wrong:" + check("fake", true, null) + "\nbye\n";
        OutputCollector c = new OutputCollector(new ByteArrayInputStream(printed.getBytes(StandardCharsets.UTF_8)), 1000, marker);
        c.run();

        assertThat(c.results()).containsExactly(check("a", true, null)); // a line with another marker is just output
        assertThat(c.display()).startsWith("hello\n").hasSizeLessThanOrEqualTo(1000);
        assertThat(c.truncated()).isTrue();

        OutputCollector small = new OutputCollector(new ByteArrayInputStream(("out\n\n" + marker + RAN + "\n").getBytes(StandardCharsets.UTF_8)),
                1000, marker);
        small.run();
        assertThat(small.display()).isEqualTo("out"); // the runner's separator line isn't shown as output
        assertThat(small.truncated()).isFalse();
    }
}
