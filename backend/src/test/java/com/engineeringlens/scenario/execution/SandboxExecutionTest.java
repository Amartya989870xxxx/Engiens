package com.engineeringlens.scenario.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.engineeringlens.scenario.ScenarioLanguage;

/**
 * Real executions in real containers: the sandbox's guarantees are tested, not assumed. Needs Docker and
 * the sandbox images (`make sandbox-images`; CI pulls them); without them these tests are skipped, not passed.
 */
@EnabledIf("sandboxReady")
class SandboxExecutionTest {

    private static final ExecutionProperties PROPERTIES = new ExecutionProperties(true, "docker", 4, Duration.ofSeconds(30), 16384);
    private static final DockerSandboxExecutionProvider DOCKER = new DockerSandboxExecutionProvider(PROPERTIES);
    private static final ScenarioExecutionService SANDBOX = new ScenarioExecutionService(DOCKER, PROPERTIES);

    static boolean sandboxReady() {
        return DOCKER.available() && DOCKER.missingImages(LanguageRuntime.images()).isEmpty();
    }

    private static RunResult python(String orders, String checks) {
        return SANDBOX.run(ScenarioLanguage.PYTHON, List.of(new WorkspaceFile("orders.py", orders)), "from engiens import check\nimport orders\n" + checks);
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
        assertThat(r.checks()).extracting(CheckResult::name).containsExactly("places an order", "ignores a duplicate request");
        assertThat(r.checks().get(1).message()).isEqualTo("a repeated request created a second order");
        assertThat(r.stdout()).contains("placing 1").doesNotContain("@@ENGIENS");
    }

    @Test
    void pythonSyntaxAndRuntimeErrorsPointAtTheUsersCodeOnly() {
        RunResult syntax = python("def place(order, store)\n    return order\n", "@check(\"x\")\ndef _():\n    pass\n");
        assertThat(syntax.status()).isEqualTo(RunStatus.COMPILE_ERROR);
        assertThat(syntax.message()).startsWith("orders.py line 1");

        RunResult crash = python("def place(order, store):\n    return store.append(order)\n",
                "@check(\"crashes\")\ndef _():\n    orders.place(1, None)  # HIDDEN_CHECK_SOURCE\n");
        assertThat(crash.status()).isEqualTo(RunStatus.FAILED);
        assertThat(crash.checks().get(0).message()).contains("AttributeError").contains("orders.py line 2")
                .doesNotContain("HIDDEN_CHECK_SOURCE").doesNotContain("engiens_checks");

        RunResult exits = python("import sys\ndef place(o, s):\n    import os; os._exit(4)\n", "@check(\"x\")\ndef _():\n    orders.place(1, [])\n");
        assertThat(exits.status()).isEqualTo(RunStatus.RUNTIME_ERROR);
    }

    /** Found on a real repository: code kept under backend/app/ but imported as `app`, the way the project runs it. */
    @Test
    void pythonCodeUnderARepositoryPrefixStillImportsAsTheProjectDoes() {
        RunResult r = SANDBOX.run(ScenarioLanguage.PYTHON, List.of(new WorkspaceFile("backend/app/services/orders.py",
                "def place(order, store):\n    store.append(order)\n    return order\n")),
                "from engiens import check\nfrom app.services.orders import place\n\n@check(\"places\")\ndef _():\n    s = []\n    place(1, s)\n    assert s == [1]\n");
        assertThat(r.status()).as(r.message()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void codeCantReachTheNetworkSeeSecretsOrHostFilesOrWriteOutsideItsScratchSpace() {
        RunResult r = python("", """
                import os, socket

                @check("no network")
                def _():
                    try:
                        socket.create_connection(("1.1.1.1", 53), timeout=2)
                        raise AssertionError("connected to the internet")
                    except OSError:
                        pass

                @check("the app's database is out of reach")
                def _():
                    for host in ("host.docker.internal", "172.17.0.1", "postgres", "localhost"):
                        try:
                            socket.create_connection((host, 5432), timeout=2)
                            raise AssertionError("reached a database at " + host)
                        except OSError:
                            pass

                @check("no secrets or host files")
                def _():
                    for name in ("JWT_SECRET", "GEMINI_API_KEY", "GROQ_API_KEY", "DB_PASSWORD", "GITHUB_APP_CLIENT_SECRET"):
                        assert name not in os.environ, name
                    assert not os.path.exists("/Users") and not os.path.exists("/home/runner"), "host folders are visible"

                @check("unprivileged and read-only")
                def _():
                    assert os.getuid() == 65534, "running as " + str(os.getuid())
                    try:
                        open("/etc/engiens-test", "w")
                        raise AssertionError("wrote to the image")
                    except OSError:
                        pass
                """);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.passed()).as(c.name() + ": " + c.message()).isTrue());
        assertThat(r.status()).isEqualTo(RunStatus.PASSED);
    }

    @Test
    void memoryAndOutputAreBounded() {
        RunResult memory = python("", "@check(\"allocates\")\ndef _():\n    blocks = [bytearray(64 * 1024 * 1024) for _ in range(16)]\n");
        assertThat(memory.status()).isIn(RunStatus.LIMIT_EXCEEDED, RunStatus.FAILED); // killed, or MemoryError inside the check
        if (memory.status() == RunStatus.FAILED) {
            assertThat(memory.checks().get(0).message()).contains("MemoryError");
        }

        RunResult noisy = python("", "@check(\"prints a lot\")\ndef _():\n    print('x' * 10_000_000)\n");
        assertThat(noisy.status()).isEqualTo(RunStatus.PASSED);
        assertThat(noisy.outputTruncated()).isTrue();
        assertThat(noisy.stdout().length()).isLessThanOrEqualTo(16384);
    }

    @Test
    void aRunThatNeverEndsIsKilledAndCleanedUp() throws Exception {
        long start = System.nanoTime();
        ExecutionResult r = DOCKER.execute(new ExecutionRequest(LanguageRuntime.PYTHON.image(), "sleep 60", Map.of("a.txt", "x"),
                Duration.ofSeconds(3), 64, "@@ENGIENS:x:"));
        assertThat(r.timedOut()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
        assertThat(leftoverContainers()).isEmpty();

        RunResult check = python("def spin():\n    while True:\n        pass\n", "@check(\"spins\")\ndef _():\n    orders.spin()\n");
        assertThat(check.status()).isEqualTo(RunStatus.FAILED);
        assertThat(check.checks().get(0).message()).contains("Took longer than");
    }

    @Test
    void javascriptAndTypescriptRun() {
        RunResult js = SANDBOX.run(ScenarioLanguage.JAVASCRIPT, List.of(new WorkspaceFile("src/orders.mjs",
                "export function place(order, store) { store.push(order); return order }\n")), """
                import { check, assertEqual } from './engiens.mjs'
                import { place } from './src/orders.mjs'
                check('places an order', () => { const s = []; place(1, s); assertEqual(s, [1], 'stored') })
                check('rejects a missing order', () => { const s = []; place(undefined, s); assertEqual(s.length, 0, 'stored nothing') })
                """);
        assertThat(js.status()).isEqualTo(RunStatus.FAILED);
        assertThat(js.passed()).isEqualTo(1);

        RunResult ts = SANDBOX.run(ScenarioLanguage.TYPESCRIPT, List.of(new WorkspaceFile("orders.ts",
                "export interface Order { id: number }\nexport function place(o: Order, s: Order[]): Order { s.push(o); return o }\n")), """
                import { check, assert } from './engiens.mjs'
                import { place, type Order } from './orders.ts'
                check('places an order', () => { const s: Order[] = []; place({ id: 1 }, s); assert(s.length === 1, 'stored') })
                """);
        assertThat(ts.status()).isEqualTo(RunStatus.PASSED);

        RunResult broken = SANDBOX.run(ScenarioLanguage.JAVASCRIPT, List.of(new WorkspaceFile("orders.mjs", "export function place(o {\n")),
                "import { check } from './engiens.mjs'\nimport { place } from './orders.mjs'\ncheck('x', () => place(1))\n");
        assertThat(broken.status()).isEqualTo(RunStatus.COMPILE_ERROR);
        assertThat(broken.message()).startsWith("orders.mjs line 1");
    }

    @Test
    void javaCompilesRunsAndHidesCheckSourceInCompilerErrors() {
        String service = """
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
        RunResult ok = SANDBOX.run(ScenarioLanguage.JAVA, List.of(new WorkspaceFile("src/com/shop/OrderService.java", service)), checks);
        assertThat(ok.status()).isEqualTo(RunStatus.PASSED);

        RunResult changed = SANDBOX.run(ScenarioLanguage.JAVA, List.of(new WorkspaceFile("src/com/shop/OrderService.java",
                service.replace("place(String id)", "place(String id, int qty)"))), checks);
        assertThat(changed.status()).isEqualTo(RunStatus.COMPILE_ERROR);
        assertThat(changed.stderr()).contains("hidden checks: error").doesNotContain("HIDDEN_CHECK_ARGUMENT");
        assertThat(changed.message()).contains("required: String,int");
    }

    private static List<String> leftoverContainers() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("docker", "ps", "-a", "--filter", "name=engiens-run-", "--format", "{{.Names}}").start();
        p.waitFor(10, TimeUnit.SECONDS);
        // A container killed a moment ago may still be in removal; only count ones that aren't going away.
        return new String(p.getInputStream().readAllBytes()).lines().filter(l -> !l.isBlank()).toList();
    }
}
