package com.engineeringlens.scenario.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.scenario.ScenarioLanguage;

import tools.jackson.databind.json.JsonMapper;

/**
 * The production execution path: Spring Boot → HTTP → the real runner service (runner/engiens_runner.py) → real
 * python3, node and javac/java. Same scenarios as the Docker sandbox tests, through the same ScenarioExecutionService,
 * so the two providers are shown to behave alike. Needs python3, node (≥ 22.6, for TypeScript) and a JDK; skipped
 * otherwise (CI installs them).
 */
@EnabledIf("runtimesPresent")
class RunnerExecutionTest {

    private static final String TOKEN = "runner-test-token-0123456789";
    private static final ExecutionProperties PROPERTIES = new ExecutionProperties(true, "docker", 2, Duration.ofSeconds(30), 16384, "runner");
    private static Process runner;
    private static String url;
    private static RemoteRunnerExecutionProvider provider;
    private static ScenarioExecutionService service;

    static boolean runtimesPresent() {
        return works("python3", "--version") && works("node", "--version") && works("javac", "-version");
    }

    private static boolean works(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Set ENGIENS_RUNNER_TEST_URL (and _TOKEN) to run these tests against a runner container instead of a local process. */
    @BeforeAll
    static void startRunner() throws Exception {
        String external = System.getenv("ENGIENS_RUNNER_TEST_URL");
        if (external != null && !external.isBlank()) {
            url = external;
            provider = new RemoteRunnerExecutionProvider(PROPERTIES, url, System.getenv("ENGIENS_RUNNER_TEST_TOKEN"), JsonMapper.builder().build());
            service = new ScenarioExecutionService(provider, PROPERTIES);
            return;
        }
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ProcessBuilder pb = new ProcessBuilder("python3", new File("../runner/engiens_runner.py").getCanonicalPath())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("RUNNER_TOKEN", TOKEN);
        runner = pb.start();
        url = "http://127.0.0.1:" + port;
        HttpClient http = HttpClient.newHttpClient();
        for (int i = 0; i < 100; i++) {
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(url + "/health")).build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode() == 200) {
                    break;
                }
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        provider = new RemoteRunnerExecutionProvider(PROPERTIES, url, TOKEN, JsonMapper.builder().build());
        service = new ScenarioExecutionService(provider, PROPERTIES);
    }

    @AfterAll
    static void stopRunner() {
        if (runner != null) {
            runner.destroyForcibly();
        }
    }

    private static RunResult python(String orders, String checks) {
        return service.run(ScenarioLanguage.PYTHON, List.of(new WorkspaceFile("orders.py", orders)), "from engiens import check\nimport orders\n" + checks);
    }

    @Test
    void springBootReachesTheRunner() {
        assertThat(provider.available()).isTrue();
        assertThat(provider.description()).isEqualTo("runner service at " + url);
    }

    @Test
    void pythonReportsEachCheck() {
        RunResult r = python("""
                def place(order, store):
                    print("placing", order)
                    store.append(order)
                    return order
                """, """
                @check("places an order")
                def _():
                    s = []
                    orders.place(1, s)
                    assert s == [1], "the order should be stored"

                @check("ignores a duplicate request")
                def _():
                    s = []
                    orders.place(1, s); orders.place(1, s)
                    assert len(s) == 1, "a repeated request created a second order"
                """);
        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.passed()).isEqualTo(1);
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.checks().get(1).message()).isEqualTo("a repeated request created a second order");
        assertThat(r.stdout()).contains("placing 1").doesNotContain("@@ENGIENS");
    }

    @Test
    void pythonCodeUnderARepositoryPrefixStillImports() {
        RunResult r = service.run(ScenarioLanguage.PYTHON, List.of(new WorkspaceFile("backend/app/services/orders.py",
                "def place(order, store):\n    store.append(order)\n    return order\n")),
                "from engiens import check\nfrom app.services.orders import place\n\n@check(\"places\")\ndef _():\n    s = []\n    place(1, s)\n    assert s == [1]\n");
        assertThat(r.status()).as(r.message()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void javascriptAndTypescriptRun() {
        RunResult js = service.run(ScenarioLanguage.JAVASCRIPT, List.of(new WorkspaceFile("src/orders.mjs",
                "export function place(order, store) { store.push(order); return order }\n")), """
                import { check, assertEqual } from './engiens.mjs'
                import { place } from './src/orders.mjs'
                check('places an order', () => { const s = []; place(1, s); assertEqual(s, [1], 'stored') })
                check('rejects a missing order', () => { const s = []; place(undefined, s); assertEqual(s.length, 0, 'stored nothing') })
                """);
        assertThat(js.status()).isEqualTo(RunStatus.FAILED);
        assertThat(js.passed()).isEqualTo(1);

        RunResult ts = service.run(ScenarioLanguage.TYPESCRIPT, List.of(new WorkspaceFile("orders.ts",
                "export interface Order { id: number }\nexport function place(o: Order, s: Order[]): Order { s.push(o); return o }\n")), """
                import { check, assert } from './engiens.mjs'
                import { place, type Order } from './orders.ts'
                check('places an order', () => { const s: Order[] = []; place({ id: 1 }, s); assert(s.length === 1, 'stored') })
                """);
        assertThat(ts.status()).as(ts.message() + " " + ts.stderr()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void javaCompilesRunsAndHidesCheckSourceInCompilerErrors() {
        String service1 = """
                package com.shop;
                import java.util.*;
                public class OrderService {
                    public final List<String> orders = new ArrayList<>();
                    public String place(String id) { orders.add(id); return id; }
                }
                """;
        String checks = """
                import com.shop.OrderService;
                public class EngiensChecks {
                    public static void register(Engiens engiens) {
                        engiens.check("places an order", () -> {
                            OrderService s = new OrderService();
                            s.place("HIDDEN_CHECK_ARGUMENT");
                            Engiens.assertEquals(1, s.orders.size(), "stored");
                        });
                    }
                }
                """;
        RunResult ok = service.run(ScenarioLanguage.JAVA, List.of(new WorkspaceFile("src/com/shop/OrderService.java", service1)), checks);
        assertThat(ok.status()).as(ok.message() + " " + ok.stderr()).isEqualTo(RunStatus.PASSED);

        RunResult changed = service.run(ScenarioLanguage.JAVA, List.of(new WorkspaceFile("src/com/shop/OrderService.java",
                service1.replace("place(String id)", "place(String id, int qty)"))), checks);
        assertThat(changed.status()).isEqualTo(RunStatus.COMPILE_ERROR);
        assertThat(changed.stderr()).doesNotContain("HIDDEN_CHECK_ARGUMENT");
    }

    @Test
    void aCheckThatNeverEndsIsReportedAndARunPastItsLimitIsKilled() {
        RunResult check = python("def spin():\n    while True:\n        pass\n", "@check(\"spins\")\ndef _():\n    orders.spin()\n");
        assertThat(check.status()).isEqualTo(RunStatus.FAILED);
        assertThat(check.checks().get(0).message()).contains("Took longer than");

        long start = System.nanoTime();
        ExecutionResult killed = provider.execute(new ExecutionRequest("python", "sleep 60", Map.of("a.txt", "x"), Duration.ofSeconds(2), 64,
                "@@ENGIENS:x:"));
        assertThat(killed.timedOut()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void invalidRequestsAndAWrongTokenFailCleanlyAsUnavailableNeverAsUserErrors() {
        assertThatThrownBy(() -> provider.execute(new ExecutionRequest("python", "true", Map.of("../escape.py", "x"), Duration.ofSeconds(5), 64,
                "@@ENGIENS:x:"))).isInstanceOf(ExecutionUnavailableException.class).hasMessageContaining("HTTP 400");

        RemoteRunnerExecutionProvider wrongToken = new RemoteRunnerExecutionProvider(PROPERTIES, url, "not-the-token-at-all-0",
                JsonMapper.builder().build());
        assertThatThrownBy(() -> wrongToken.execute(new ExecutionRequest("python", "true", Map.of("a.txt", "x"), Duration.ofSeconds(5), 64,
                "@@ENGIENS:x:"))).isInstanceOf(ExecutionUnavailableException.class).hasMessageContaining("HTTP 401");
    }

    @Test
    void anUnreachableOrUnconfiguredRunnerIsUnavailableAndRunSaysSo() {
        RemoteRunnerExecutionProvider down = new RemoteRunnerExecutionProvider(PROPERTIES, "http://127.0.0.1:1", TOKEN, JsonMapper.builder().build());
        assertThat(down.available()).isFalse();
        RemoteRunnerExecutionProvider unset = new RemoteRunnerExecutionProvider(PROPERTIES, "", "", JsonMapper.builder().build());
        assertThat(unset.available()).isFalse();
        assertThatThrownBy(() -> new ScenarioExecutionService(down, PROPERTIES).run(ScenarioLanguage.PYTHON,
                List.of(new WorkspaceFile("orders.py", "x = 1\n")), "from engiens import check\n"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SCENARIO_EXECUTION_UNAVAILABLE"));
    }
}
