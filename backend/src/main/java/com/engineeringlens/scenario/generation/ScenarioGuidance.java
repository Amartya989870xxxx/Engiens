package com.engineeringlens.scenario.generation;

import java.util.EnumMap;
import java.util.Map;

import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;
import com.engineeringlens.scenario.Seniority;

/**
 * What each role cares about, how each seniority changes the engineering depth (not the length) of a
 * scenario, and the exact check API the AI must write against for each sandbox language.
 */
final class ScenarioGuidance {

    private ScenarioGuidance() {
    }

    static final Map<ScenarioRole, String> ROLE_FOCUS = new EnumMap<>(Map.ofEntries(
            Map.entry(ScenarioRole.FRONTEND_ENGINEER,
                    "state management, API integration, rendering cost, error and loading states, client architecture, accessibility where the code supports it"),
            Map.entry(ScenarioRole.BACKEND_ENGINEER,
                    "API behaviour, persistence, transactions, concurrency, error handling, service boundaries, performance"),
            Map.entry(ScenarioRole.FULL_STACK_ENGINEER,
                    "the contract between client and server, validation on both sides, end-to-end data flow, failure handling across the boundary"),
            Map.entry(ScenarioRole.DEVOPS_ENGINEER, "CI/CD, deployment, environment configuration, reliability, operational behaviour"),
            Map.entry(ScenarioRole.INFRASTRUCTURE_ENGINEER,
                    "runtime environment, containers, resource limits, configuration, failure recovery, capacity"),
            Map.entry(ScenarioRole.CLOUD_ENGINEER, "architecture on cloud services, scaling, service boundaries, storage and networking, failure modes"),
            Map.entry(ScenarioRole.NETWORK_ENGINEER, "connectivity, retries and timeouts, service boundaries, network assumptions, latency"),
            Map.entry(ScenarioRole.AI_ENGINEER, "model integration, inference behaviour, prompt/data flow, reliability and cost of AI calls"),
            Map.entry(ScenarioRole.ML_ENGINEER, "data flow, feature handling, training/inference correctness, evaluation"),
            Map.entry(ScenarioRole.MLOPS_ENGINEER, "model deployment, reproducibility, monitoring, data and model versioning"),
            Map.entry(ScenarioRole.AI_ML_ENGINEER, "model integration, data flow, evaluation, inference reliability"),
            Map.entry(ScenarioRole.AI_RESEARCHER, "experiment design, evaluation methodology, correctness of measurements, reproducibility"),
            Map.entry(ScenarioRole.SOFTWARE_ARCHITECT, "boundaries, coupling, failure domains, scalability, trade-offs, migration strategy"),
            Map.entry(ScenarioRole.BROAD_ENGINEERING, "whatever engineering problems this repository's code most clearly supports")));

    static final Map<Seniority, String> SENIORITY_DEPTH = new EnumMap<>(Map.of(
            Seniority.BEGINNER, "fundamental correctness, basic testing, understandable fixes, straightforward reliability. One clear problem, "
                    + "small scope, a fix in one place.",
            Seniority.SDE1, "practical production engineering: API, database, testing and error handling, with moderate trade-offs. "
                    + "A realistic bug or gap with a contained fix.",
            Seniority.SDE2, "deeper design: scalability, concurrency, reliability and stronger trade-offs. Problems whose fix needs "
                    + "reasoning about interactions between components.",
            Seniority.SDE3, "system-level reasoning: large-scale impact, failure domains, complex trade-offs, migration strategy. "
                    + "Several valid approaches with real costs.",
            Seniority.SENIOR_ARCHITECT, "architecture: boundaries, long-term evolution, organisational and operational trade-offs, "
                    + "scale and resilience. Decisions, not just code."));

    private static final String COMMON_HARNESS_RULES = """
            Rules for the executable part:
            - The workspace is a small, self-contained extract ADAPTED from the repository files shown: keep the repository's names,
              structure and the relevant logic recognisable, but replace frameworks, databases, queues and network calls with tiny
              in-memory stand-ins so it runs with the standard library only. Mark stand-ins and other support files
              "editable": false; the files the user should change "editable": true. At most 8 files, small.
            - The starter workspace must load/compile and run, but must FAIL at least one check: it reproduces the problem.
              Don't leave the fix, hints or TODO comments that give it away.
            - Write 3 to 6 checks (at most 8). Test behaviour through public functions/classes, not implementation details, so
              different valid solutions pass. Include at least one check that existing correct behaviour is preserved.
            - Checks must be deterministic and fast: no network, no files outside the workspace, no wall-clock dependence,
              no sleeps over 1 second, seeded randomness only. Each check has a 5 second limit; threads are fine for concurrency.
            - Assertion messages describe the expected behaviour ("a repeated request created a second order"), never the fix.
            - checkNames lists the exact names passed to check(...), in order.
            - referenceSolution.files gives the FULL new content of each editable file it changes (same paths). It must pass
              every check.
            """;

    static String languageContract(ScenarioLanguage language) {
        return switch (language) {
            case PYTHON -> """
                    Language PYTHON (Python 3.12, standard library only: no fastapi, sqlalchemy, pydantic, requests...).
                    Workspace files are .py modules. The workspace root is the import root (packages need no __init__.py):
                    a module imported as app.services.orders must be at app/services/orders.py. Drop repository prefixes
                    such as backend/ from paths so imports in the starter, the checks and the solution all resolve.
                    Checks file (its whole content goes in checks.source):
                      from engiens import check
                      from app.services.orders import place_order      # import from workspace modules
                      @check("ignores a repeated request")
                      def _():
                          ...
                          assert condition, "message describing expected behaviour"
                    """;
            case JAVASCRIPT -> """
                    Language JAVASCRIPT (Node 24 ES modules, built-in modules only: no npm packages, no JSX).
                    Workspace files are .mjs modules.
                    Checks file (its whole content goes in checks.source):
                      import { check, assert, assertEqual, assertRejects } from './engiens.mjs'
                      import { placeOrder } from './src/orders.mjs'
                      check('ignores a repeated request', async () => {
                        ...
                        assert(condition, 'message'); assertEqual(actual, expected, 'message')
                      })
                    """;
            case TYPESCRIPT -> """
                    Language TYPESCRIPT (Node 24 runs .ts directly by stripping types; built-in modules only: no npm packages, no JSX).
                    Use only erasable TypeScript: types, interfaces and annotations are fine; NO enum, namespace, parameter
                    properties or decorators. Relative imports must end in ".ts". Use `import { type X }` for type-only imports.
                    Checks file (its whole content goes in checks.source):
                      import { check, assert, assertEqual, assertRejects } from './engiens.mjs'
                      import { placeOrder } from './src/orders.ts'
                      check('ignores a repeated request', async () => { ...; assert(condition, 'message') })
                    """;
            case JAVA -> """
                    Language JAVA (Java 21, standard library only: no Spring, JPA, Lombok or JUnit).
                    Place each file under a path matching its package, e.g. src/com/shop/OrderService.java. All files are compiled together.
                    Checks file (default package; its whole content goes in checks.source):
                      import com.shop.OrderService;
                      public class EngiensChecks {
                          public static void register(Engiens engiens) {
                              engiens.check("ignores a repeated request", () -> {
                                  ...
                                  Engiens.assertEquals(expected, actual, "message");
                                  // also: Engiens.assertTrue(cond, "m"), assertFalse, assertThrows(Type.class, () -> ..., "m"), fail("m")
                              });
                          }
                      }
                    assertEquals uses Objects.equals: compare like types (1L with a long, not 1).
                    """;
        } + COMMON_HARNESS_RULES;
    }
}
