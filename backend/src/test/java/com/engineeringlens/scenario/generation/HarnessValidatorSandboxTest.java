package com.engineeringlens.scenario.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.execution.DockerSandboxExecutionProvider;
import com.engineeringlens.scenario.execution.ExecutionProperties;
import com.engineeringlens.scenario.execution.LanguageRuntime;
import com.engineeringlens.scenario.execution.ScenarioExecutionService;
import com.engineeringlens.scenario.model.ScenarioValidation;

/** Harness validation with real containers: a good scenario is proven, broken ones are rejected with a usable reason. */
@EnabledIf("sandboxReady")
class HarnessValidatorSandboxTest {

    private static final ExecutionProperties PROPERTIES = new ExecutionProperties(true, "docker", 2, Duration.ofSeconds(30), 16384, "docker");
    private static final DockerSandboxExecutionProvider DOCKER = new DockerSandboxExecutionProvider(PROPERTIES);
    private static final HarnessValidator VALIDATOR = new HarnessValidator(new ScenarioExecutionService(DOCKER, PROPERTIES));

    static boolean sandboxReady() {
        return DOCKER.available() && DOCKER.missingImages(LanguageRuntime.images()).isEmpty();
    }

    private static final String STORE = """
            class InMemoryOrders:
                def __init__(self):
                    self.rows = []
                def insert(self, order):
                    self.rows.append(dict(order))
            """;
    private static final String STARTER = """
            def create_order(db, request):
                db.insert({"key": request["idempotency_key"], "item": request["item"]})
                return {"status": "created"}
            """;
    private static final String FIXED = """
            def create_order(db, request):
                if any(r["key"] == request["idempotency_key"] for r in db.rows):
                    return {"status": "duplicate"}
                db.insert({"key": request["idempotency_key"], "item": request["item"]})
                return {"status": "created"}
            """;
    private static final String CHECKS = """
            from engiens import check
            from store import InMemoryOrders
            from orders import create_order

            @check("creates an order")
            def _():
                db = InMemoryOrders()
                create_order(db, {"idempotency_key": "a", "item": "book"})
                assert len(db.rows) == 1, "a new request should create one order"

            @check("ignores a repeated request")
            def _():
                db = InMemoryOrders()
                create_order(db, {"idempotency_key": "a", "item": "book"})
                create_order(db, {"idempotency_key": "a", "item": "book"})
                assert len(db.rows) == 1, "a repeated request created a second order"

            @check("keeps different requests apart")
            def _():
                db = InMemoryOrders()
                create_order(db, {"idempotency_key": "a", "item": "book"})
                create_order(db, {"idempotency_key": "b", "item": "pen"})
                assert len(db.rows) == 2, "different requests should both be stored"
            """;

    private static GeneratedScenario scenario(String starter, String fixed, List<String> names) {
        return new GeneratedScenario(1, "t", "s", "i", "c", "task", List.of(), List.of(), List.of(), List.of("idempotency"),
                List.of(new GeneratedScenario.Criterion("a", "b"), new GeneratedScenario.Criterion("c", "d")), "r",
                new GeneratedScenario.Workspace(ScenarioLanguage.PYTHON, List.of(new GeneratedScenario.File("store.py", STORE, false),
                        new GeneratedScenario.File("orders.py", starter, true))),
                new GeneratedScenario.Checks(CHECKS, names),
                new GeneratedScenario.ReferenceSolution(List.of(new GeneratedScenario.SolutionFile("orders.py", fixed)), "explained"));
    }

    private static final List<String> NAMES = List.of("creates an order", "ignores a repeated request", "keeps different requests apart");

    @Test
    void aWorkingScenarioIsProven() {
        ScenarioValidation v = VALIDATOR.validate(scenario(STARTER, FIXED, NAMES));
        assertThat(v.starter().status()).isEqualTo("FAILED");
        assertThat(v.starter().passed()).isEqualTo(2);
        assertThat(v.reference().status()).isEqualTo("PASSED");
        assertThat(v.reference().total()).isEqualTo(3);
    }

    @Test
    void brokenScenariosAreRejectedWithAReasonTheModelCanFix() {
        assertThatThrownBy(() -> VALIDATOR.validate(scenario(FIXED, FIXED, NAMES)))
                .hasMessageContaining("already passes every check");
        assertThatThrownBy(() -> VALIDATOR.validate(scenario(STARTER, STARTER, NAMES)))
                .hasMessageContaining("reference solution must pass every check").hasMessageContaining("ignores a repeated request");
        assertThatThrownBy(() -> VALIDATOR.validate(scenario("def create_order(db, request)\n", FIXED, NAMES)))
                .hasMessageContaining("must load and run").hasMessageContaining("orders.py line 1");
        assertThatThrownBy(() -> VALIDATOR.validate(scenario(STARTER, FIXED, List.of("creates an order", "something else"))))
                .hasMessageContaining("checkNames must list exactly the checks registered");
    }
}
